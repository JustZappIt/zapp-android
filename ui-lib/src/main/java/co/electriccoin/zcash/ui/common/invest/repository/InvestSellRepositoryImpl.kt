package co.electriccoin.zcash.ui.common.invest.repository

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.invest.model.Erc191SignedData
import co.electriccoin.zcash.ui.common.invest.model.GenerateIntentRequest
import co.electriccoin.zcash.ui.common.invest.model.InvestAsset
import co.electriccoin.zcash.ui.common.invest.model.PreparedSell
import co.electriccoin.zcash.ui.common.invest.model.SellAmount
import co.electriccoin.zcash.ui.common.invest.model.SellEstimate
import co.electriccoin.zcash.ui.common.invest.model.SellIntentRefusedException
import co.electriccoin.zcash.ui.common.invest.model.SellProgress
import co.electriccoin.zcash.ui.common.invest.model.SubmitIntentRequest
import co.electriccoin.zcash.ui.common.invest.provider.InvestApiException
import co.electriccoin.zcash.ui.common.invest.provider.InvestApiProvider
import co.electriccoin.zcash.ui.common.invest.provider.InvestSellCheckpointStorageProvider
import co.electriccoin.zcash.ui.common.invest.provider.InvestTradeCheckpoint
import co.electriccoin.zcash.ui.common.invest.provider.PrivateAccountKeyProvider
import co.electriccoin.zcash.ui.common.invest.provider.PrivateAccountSession
import co.electriccoin.zcash.ui.common.invest.repository.InvestQuoteParams.PRICE_HELD
import co.electriccoin.zcash.ui.common.invest.repository.InvestQuoteParams.PRICE_MAX_AGE
import co.electriccoin.zcash.ui.common.invest.repository.InvestQuoteParams.ZEC_DECIMALS
import co.electriccoin.zcash.ui.common.invest.repository.InvestSellChecks.Resolved
import co.electriccoin.zcash.ui.common.model.near.SwapStatus
import co.electriccoin.zcash.ui.common.model.near.SwapStatusResponseDto
import co.electriccoin.zcash.ui.common.provider.OfframpBridgeWallet
import co.electriccoin.zcash.ui.common.repository.BiometricRepository
import co.electriccoin.zcash.ui.common.repository.BiometricRequest
import co.electriccoin.zcash.ui.design.util.stringRes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import xyz.justzappit.evm.intents.IntentTransferSigner
import xyz.justzappit.evm.intents.IntentTransferSigner.ExpectedTransfer
import xyz.justzappit.evm.intents.IntentTransferSigner.VerifyResult
import java.math.BigDecimal
import java.math.BigInteger
import java.net.HttpURLConnection.HTTP_NOT_FOUND
import java.util.concurrent.TimeoutException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Sells a holding back to ZEC: the quote's deposit comes from the private account and its refund goes back there;
 * the ZEC is paid to a fresh shielded address. The generated intent is checked before it is shown and again as it
 * is signed.
 */
@Suppress("TooManyFunctions")
internal class InvestSellRepositoryImpl(
    private val api: InvestApiProvider,
    private val session: PrivateAccountSession,
    private val keys: PrivateAccountKeyProvider,
    private val wallet: OfframpBridgeWallet,
    private val investRepository: InvestRepository,
    private val swapAssets: InvestSwapAssetSource,
    private val biometricRepository: BiometricRepository,
    private val checkpoints: InvestSellCheckpointStorageProvider,
    private val trades: InvestTradeGuard,
    private val now: () -> Instant = { Clock.System.now() },
    private val pollIntervalMillis: Long = InvestStatusPolling.INTERVAL_MS,
) : InvestSellRepository {
    @Volatile
    private var estimateRecipient: String? = null
    private val submitting = mutableSetOf<String>()
    private val submittingMutex = Mutex()

    @Suppress("ReturnCount")
    override suspend fun estimateSell(
        asset: InvestAsset,
        amount: SellAmount,
    ): SellEstimate {
        val order =
            when (val resolved = resolve(asset, amount)) {
                is Resolved.Order -> resolved
                is Resolved.Refused -> return resolved.estimate
            }
        val recipient = estimateRecipient ?: wallet.zcashAddress().also { estimateRecipient = it }
        val accountId = keys.accountId()
        val request = InvestSellChecks.quoteRequest(dry = true, asset, order.baseUnits, recipient, accountId, now())
        val response =
            try {
                api.requestQuote(request)
            } catch (_: InvestApiException.NoPrice) {
                return SellEstimate.NoPrice
            } catch (e: InvestApiException.Api) {
                if (!InvestSellChecks.isAmountTooSmall(e.status, e.apiMessage)) throw e
                val minimum = SellEstimate.BelowMinimum(InvestRepository.MINIMUM_USD)
                return if (order.isAll) SellEstimate.TooSmallToSell else minimum
            }
        if (InvestSellChecks.isBelowMinimum(order, response.quote.amountInUsd)) {
            return SellEstimate.BelowMinimum(InvestRepository.MINIMUM_USD)
        }
        return SellEstimate.Priced(
            unitsIn = order.units,
            usdIn = response.quote.amountInUsd,
            zecOut = response.quote.amountOutFormatted,
            usdOut = response.quote.amountOutUsd,
            feesUsd = (response.quote.amountInUsd - response.quote.amountOutUsd).max(BigDecimal.ZERO),
            withdrawFeeZec = response.quote.withdrawFee?.movePointLeft(ZEC_DECIMALS),
            etaSeconds = response.quote.timeEstimate,
        )
    }

    override suspend fun prepareSell(
        asset: InvestAsset,
        amount: SellAmount,
    ): PreparedSell {
        check(investRepository.isAccountSupported()) { "Invest isn't available for this account" }
        trades.checkNoTrade(asset.assetId)
        val order =
            when (val resolved = resolve(asset, amount)) {
                is Resolved.Order -> resolved
                is Resolved.Refused -> error("Can't sell: ${resolved.estimate}")
            }
        val accountId = keys.accountId()
        // A fresh address per sale, so the payout links to no other payment.
        val recipient = wallet.zcashAddress()
        val response =
            api.requestQuote(
                InvestSellChecks.quoteRequest(dry = false, asset, order.baseUnits, recipient, accountId, now()),
            )
        InvestSellChecks.requireEcho(response, asset, order, accountId, recipient)
        val depositAddress =
            requireNotNull(response.quote.depositAddress?.takeIf { it.isNotBlank() }) {
                "1Click returned an executable quote with no deposit address"
            }
        val generated =
            api.generateIntent(
                GenerateIntentRequest(
                    standard = IntentTransferSigner.STANDARD,
                    signerId = accountId,
                    depositAddress = depositAddress,
                ),
            )
        require(generated.intent.standard == IntentTransferSigner.STANDARD) {
            "generate-intent answered in another standard"
        }
        val payload = generated.intent.payload
        val expected = expected(depositAddress, asset, order.baseUnits.toString())
        val verified = IntentTransferSigner.verify(payload, accountId, expected, now().toEpochMilliseconds())
        val intentDeadline =
            when (verified) {
                is VerifyResult.Verified -> Instant.fromEpochMilliseconds(verified.deadlineMillis)
                is VerifyResult.Refused -> throw SellIntentRefusedException(verified.reason, generated.correlationId)
            }
        val quoteDeadline =
            requireNotNull(response.quote.deadline) { "1Click returned an executable quote with no deadline" }
        return PreparedSell(
            asset = asset,
            units = order.units,
            usdIn = response.quote.amountInUsd,
            zecOutExpected = response.quote.amountOutFormatted,
            zecOutMin = response.quote.minAmountOut.movePointLeft(ZEC_DECIMALS),
            feesUsd = (response.quote.amountInUsd - response.quote.amountOutUsd).max(BigDecimal.ZERO),
            withdrawFeeZec = response.quote.withdrawFee?.movePointLeft(ZEC_DECIMALS),
            etaSeconds = response.quote.timeEstimate,
            expiresAt = minOf(now() + PRICE_HELD, quoteDeadline - SUBMIT_MARGIN, intentDeadline - SUBMIT_MARGIN),
            signedMessage = payload,
            depositAddress = depositAddress,
            baseUnits = order.baseUnits.toString(),
            intentDeadline = intentDeadline,
        )
    }

    override suspend fun executeSell(prepared: PreparedSell): String {
        check(now() < prepared.expiresAt) { PRICE_EXPIRED }
        check(investRepository.isAccountSupported()) { "Invest isn't available for this account" }
        val assetId = prepared.asset.assetId
        submittingMutex.withLock {
            check(assetId !in submitting) { "A sale of this stock is already being submitted" }
            trades.checkNoTrade(assetId)
            submitting += assetId
        }
        try {
            biometricRepository.requestBiometrics(
                BiometricRequest(
                    message =
                        stringRes(
                            R.string.authentication_system_ui_subtitle,
                            stringRes(R.string.authentication_use_case_send_funds),
                        ),
                ),
            )
            // The prompt can outlast the price hold; the signature must not.
            check(now() < prepared.expiresAt) { PRICE_EXPIRED }
            val signed = sign(prepared)
            val checkpoint = trades.withLock { recordSale(prepared) }
            submit(checkpoint, signed)
        } finally {
            withContext(NonCancellable) { submittingMutex.withLock { submitting -= assetId } }
        }
        return prepared.depositAddress
    }

    /**
     * Under the trade lock: no other trade of the stock, then the balance before the sale, then the checkpoint,
     * written before the intent leaves so a crash resumes polling it.
     */
    private suspend fun recordSale(prepared: PreparedSell): InvestTradeCheckpoint {
        check(now() < prepared.expiresAt) { PRICE_EXPIRED }
        trades.checkNoTrade(prepared.asset.assetId)
        // Bounded, as every buy waits on this lock.
        val heldBefore =
            withTimeoutOrNull(BALANCE_READ_TIMEOUT) { heldBaseUnits(prepared.asset.assetId) }
                ?: throw InvestApiException.Unreachable(TimeoutException("Balance read timed out"))
        check(now() < prepared.expiresAt) { PRICE_EXPIRED }
        val checkpoint =
            InvestTradeCheckpoint(
                depositAddress = prepared.depositAddress,
                assetId = prepared.asset.assetId,
                createdAtMillis = now().toEpochMilliseconds(),
                intentDeadlineMillis = prepared.intentDeadline.toEpochMilliseconds(),
                baseUnits = prepared.baseUnits,
                heldBeforeBaseUnits = heldBefore.toString(),
            )
        checkpoints.add(checkpoint)
        return checkpoint
    }

    private suspend fun sign(prepared: PreparedSell): IntentTransferSigner.SignResult.Signed {
        val expected = expected(prepared.depositAddress, prepared.asset, prepared.baseUnits)
        val result =
            keys.withKey { key ->
                IntentTransferSigner.sign(key, prepared.signedMessage, expected, now().toEpochMilliseconds())
            }
        return when (result) {
            is IntentTransferSigner.SignResult.Signed -> result
            is IntentTransferSigner.SignResult.Refused -> throw SellIntentRefusedException(result.reason, null)
        }
    }

    /**
     * Submits once and never re-signs. Only a failure proving nothing was accepted drops the checkpoint; anything
     * else may mean the intent runs, so polling finds out.
     */
    private suspend fun submit(
        checkpoint: InvestTradeCheckpoint,
        signed: IntentTransferSigner.SignResult.Signed,
    ) {
        try {
            val response =
                api.submitIntent(
                    SubmitIntentRequest(
                        signedData =
                            Erc191SignedData(
                                standard = signed.standard,
                                payload = signed.payload,
                                signature = signed.signature,
                            ),
                    ),
                )
            rememberIntentHash(checkpoint, response.intentHash)
        } catch (e: InvestApiException) {
            val definite =
                when (e) {
                    is InvestApiException.TorUnavailable, is InvestApiException.Unauthorized -> true
                    is InvestApiException.Api -> InvestSellChecks.isDefiniteRefusal(e.status, e.apiMessage)
                    else -> false
                }
            if (definite) {
                checkpoints.remove(checkpoint.depositAddress)
                throw e
            }
        }
    }

    private suspend fun rememberIntentHash(
        checkpoint: InvestTradeCheckpoint,
        intentHash: String,
    ) {
        try {
            checkpoints.add(checkpoint.copy(intentHash = intentHash))
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") _: Exception
        ) {
            // The sale was accepted; support falls back to the deposit address.
        }
    }

    override fun observeSell(depositAddress: String): Flow<SellProgress> =
        flow {
            // Read once: settling as not sold removes the record, and a later reader couldn't tell this sale apart
            // from one never recorded.
            val poll = Poll(depositAddress, checkpoint(depositAddress))
            while (true) {
                val progress = poll.next()
                if (progress != null) {
                    // Settled first: a collector may stop at the final state and never resume this flow.
                    if (progress.isFinal) poll.settle(progress)
                    emit(progress)
                    if (progress.isFinal) break
                }
                delay(pollIntervalMillis)
            }
        }

    private class Poll(
        val depositAddress: String,
        /** Null for a sale this phone isn't following. */
        val record: InvestTradeCheckpoint?,
    ) {
        val intentDeadline: Instant? = record?.intentDeadlineMillis?.let(Instant::fromEpochMilliseconds)
        var rejected = 0
    }

    private suspend fun Poll.next(): SellProgress? =
        try {
            toProgress(api.checkStatus(depositAddress)).also { rejected = 0 }
        } catch (e: CancellationException) {
            throw e
        } catch (e: InvestApiException.Api) {
            onRejected(e)
        } catch (_: InvestApiException) {
            null
        }

    // Status may not know the quote until its intent is seen: waiting until the intent's deadline, then the balance
    // decides. Without a record there's no deadline to wait for, so repeated rejections end in support.
    private suspend fun Poll.onRejected(e: InvestApiException.Api): SellProgress? {
        val unknownUntil = intentDeadline?.takeIf { e.status == HTTP_NOT_FOUND }
        rejected = if (unknownUntil == null && e.status in InvestStatusPolling.PERMANENT_ERRORS) rejected + 1 else 0
        return when {
            unknownUntil != null && now() > unknownUntil + NOT_SOLD_GRACE -> notSoldOrAttention()
            unknownUntil != null -> SellProgress.Authorised(depositAddress)
            rejected >= InvestStatusPolling.MAX_REJECTED -> needsAttention()
            else -> null
        }
    }

    override suspend fun dismissSell(depositAddress: String) {
        checkpoints.remove(depositAddress)
    }

    override fun clearWalletData() {
        estimateRecipient = null
    }

    private suspend fun Poll.settle(progress: SellProgress) {
        if (progress !is SellProgress.NeedsAttention) checkpoints.remove(progress.depositAddress)
        if (record == null || progress is SellProgress.NeedsAttention) return
        try {
            investRepository.refreshHoldings()
        } catch (e: CancellationException) {
            throw e
        } catch (_: InvestApiException) {
            // The holdings catch up on the next refresh.
        }
    }

    private suspend fun Poll.toProgress(status: SwapStatusResponseDto): SellProgress? =
        when (status.status) {
            SwapStatus.PENDING_DEPOSIT -> {
                // Without a record, the intent ran out at most the signer's limit after the quote's deadline.
                val runsUntil =
                    intentDeadline ?: status.quoteResponse.quote.deadline
                        ?.plus(MAX_INTENT_TTL)
                if (runsUntil != null && now() > runsUntil + NOT_SOLD_GRACE) {
                    notSoldOrAttention()
                } else {
                    SellProgress.Authorised(depositAddress)
                }
            }

            SwapStatus.KNOWN_DEPOSIT_TX, SwapStatus.INCOMPLETE_DEPOSIT, SwapStatus.PROCESSING -> {
                SellProgress.Selling(depositAddress)
            }

            SwapStatus.SUCCESS -> {
                SellProgress.Sent(depositAddress, status.swapDetails?.amountOutFormatted)
            }

            SwapStatus.REFUNDED -> {
                SellProgress.ReturnedToAccount(depositAddress)
            }

            SwapStatus.FAILED -> {
                needsAttention()
            }

            null -> {
                null
            }
        }

    /**
     * An intent past its deadline can't run, but 1Click not having seen it doesn't prove it never ran
     * (near/intents#356): the stock counts as unsold only while the private balance still holds it.
     */
    @Suppress("ReturnCount")
    private suspend fun Poll.notSoldOrAttention(): SellProgress {
        val before = record?.heldBeforeBaseUnits?.toBigIntegerOrNull()
        val sold = record?.baseUnits?.toBigIntegerOrNull()
        if (record == null || before == null || sold == null) return needsAttention()
        val held =
            try {
                heldBaseUnits(record.assetId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: InvestApiException) {
                return SellProgress.Authorised(depositAddress)
            }
        return if (held > before - sold) SellProgress.NotSold(depositAddress) else needsAttention()
    }

    // The intent hash is noted just after submitting, so the record is read again for it.
    private suspend fun Poll.needsAttention() =
        SellProgress.NeedsAttention(
            depositAddress = depositAddress,
            reference = checkpoint(depositAddress)?.intentHash ?: record?.intentHash ?: depositAddress,
        )

    private suspend fun checkpoint(depositAddress: String): InvestTradeCheckpoint? =
        checkpoints.observe().first().firstOrNull { it.depositAddress == depositAddress }

    private suspend fun resolve(
        asset: InvestAsset,
        amount: SellAmount,
    ): Resolved {
        val decimals =
            requireNotNull(swapAssets.investSwapAssets().firstOrNull { it.assetId == asset.assetId }?.decimals) {
                "1Click no longer lists this stock"
            }
        val market = investRepository.market.value
        if (market == null || now() - market.updatedAt > PRICE_MAX_AGE) {
            try {
                investRepository.refreshMarket()
            } catch (e: CancellationException) {
                throw e
            } catch (_: InvestApiException) {
                // Without a price, the quote's own USD value is held to the minimum.
            }
        }
        val price =
            investRepository.market.value
                ?.takeIf { now() - it.updatedAt <= PRICE_MAX_AGE }
                ?.assets
                ?.firstOrNull { it.asset == asset }
                ?.usdPrice
        return InvestSellChecks.resolve(amount, heldBaseUnits(asset.assetId), decimals, price)
    }

    private suspend fun heldBaseUnits(assetId: String): BigInteger =
        session.balances().privateBaseUnits()[assetId] ?: BigInteger.ZERO

    private fun expected(
        depositAddress: String,
        asset: InvestAsset,
        baseUnits: String,
    ) = ExpectedTransfer(receiverId = depositAddress, tokenId = asset.assetId, amount = baseUnits)

    private companion object {
        const val PRICE_EXPIRED = "The price is no longer held; prepare the sale again"

        // Left before either deadline when the hold ends, for the submission itself.
        val SUBMIT_MARGIN = 20.seconds

        // The signer's cap on how far out an intent's deadline may be.
        val MAX_INTENT_TTL = IntentTransferSigner.DEFAULT_MAX_TTL_MILLIS.milliseconds

        // Covers clock differences before a passed deadline is trusted.
        val NOT_SOLD_GRACE = 5.minutes
        val BALANCE_READ_TIMEOUT = 30.seconds
    }
}
