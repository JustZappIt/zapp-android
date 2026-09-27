package co.electriccoin.zcash.ui.common.invest.repository

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.invest.model.Erc191SignedData
import co.electriccoin.zcash.ui.common.invest.model.GenerateIntentRequest
import co.electriccoin.zcash.ui.common.invest.model.InvestAsset
import co.electriccoin.zcash.ui.common.invest.model.InvestAssets
import co.electriccoin.zcash.ui.common.invest.model.PreparedSell
import co.electriccoin.zcash.ui.common.invest.model.SellAmount
import co.electriccoin.zcash.ui.common.invest.model.SellEstimate
import co.electriccoin.zcash.ui.common.invest.model.SellIntentRefusedException
import co.electriccoin.zcash.ui.common.invest.model.SellProgress
import co.electriccoin.zcash.ui.common.invest.model.SubmitIntentRequest
import co.electriccoin.zcash.ui.common.invest.provider.InvestApiException
import co.electriccoin.zcash.ui.common.invest.provider.InvestApiProvider
import co.electriccoin.zcash.ui.common.invest.provider.InvestBuyCheckpoint
import co.electriccoin.zcash.ui.common.invest.provider.InvestSellCheckpointStorageProvider
import co.electriccoin.zcash.ui.common.invest.provider.PrivateAccountKeyProvider
import co.electriccoin.zcash.ui.common.invest.provider.PrivateAccountSession
import co.electriccoin.zcash.ui.common.invest.repository.InvestSellChecks.Resolved
import co.electriccoin.zcash.ui.common.model.near.Confidentiality
import co.electriccoin.zcash.ui.common.model.near.QuoteRequest
import co.electriccoin.zcash.ui.common.model.near.RecipientType
import co.electriccoin.zcash.ui.common.model.near.RefundType
import co.electriccoin.zcash.ui.common.model.near.SwapStatus
import co.electriccoin.zcash.ui.common.model.near.SwapStatusResponseDto
import co.electriccoin.zcash.ui.common.model.near.SwapType
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xyz.justzappit.evm.intents.IntentTransferSigner
import xyz.justzappit.evm.intents.IntentTransferSigner.ExpectedTransfer
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Sells a holding back to ZEC. The quote's deposit comes from the private account and the refund goes back
 * to it; the ZEC is paid to a fresh shielded address in this wallet. The intent `generate-intent` returns
 * is checked by [IntentTransferSigner] before it is shown, and again as it is signed. One sale per stock
 * runs at a time, so a pending sale's balance check can't be confused by another.
 *
 * UNVERIFIED end to end: see [InvestSellRepository].
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
    private val now: () -> Instant = { Clock.System.now() },
    private val pollIntervalMillis: Long = DEFAULT_POLL_INTERVAL_MS,
) : InvestSellRepository {
    @Volatile
    private var estimateRecipient: String? = null
    private val submitting = mutableSetOf<String>()
    private val submittingMutex = Mutex()

    override val pendingSells: Flow<List<String>> = checkpoints.observe().map { list -> list.map { it.depositAddress } }

    // Each early return is one of the estimate's outcomes, in the order the screen explains them.
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
        val response =
            try {
                api.requestQuote(quoteRequest(dry = true, asset, order.baseUnits, recipient, keys.accountId()))
            } catch (_: InvestApiException.NoPrice) {
                return SellEstimate.NoPrice
            } catch (e: InvestApiException.Api) {
                // Selling all of a holding worth less than the fixed fees: 1Click says the amount is too low.
                if (e.isAmountTooLow()) return SellEstimate.BelowMinimum(InvestRepository.MINIMUM_USD)
                throw e
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
        check(!hasSaleInFlight(asset)) { SALE_IN_FLIGHT }
        val order =
            when (val resolved = resolve(asset, amount)) {
                is Resolved.Order -> resolved
                is Resolved.Refused -> error("Can't sell: ${resolved.estimate}")
            }
        val accountId = keys.accountId()
        // A fresh shielded address per sale, so the payout isn't linked to any other payment.
        val recipient = wallet.zcashAddress()
        val response = api.requestQuote(quoteRequest(dry = false, asset, order.baseUnits, recipient, accountId))
        InvestSellChecks.requireEcho(response, asset, order, accountId, recipient)
        val depositAddress =
            requireNotNull(response.quote.depositAddress?.takeIf { it.isNotBlank() }) {
                "1Click returned an executable quote with no deposit address"
            }
        val generated =
            api.generateIntent(
                GenerateIntentRequest(standard = STANDARD, signerId = accountId, depositAddress = depositAddress),
            )
        require(generated.intent.standard == STANDARD) { "generate-intent answered in another standard" }
        val payload = generated.intent.payload
        val expected = expected(depositAddress, asset, order.baseUnits.toString())
        IntentTransferSigner.verify(payload, accountId, expected, now().toEpochMilliseconds())?.let { rejection ->
            throw SellIntentRefusedException(rejection, generated.correlationId)
        }
        val intentDeadline = payloadDeadline(payload)
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
            // Signed and submitted with room to spare before either deadline.
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
        val depositAddress = prepared.depositAddress
        val assetId = prepared.asset.assetId
        submittingMutex.withLock {
            check(assetId !in submitting) { SALE_IN_FLIGHT }
            check(!hasSaleInFlight(prepared.asset)) { SALE_IN_FLIGHT }
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
            // Read before anything leaves, so a sale that never reports back can still be told from one that ran.
            val heldBefore = heldBaseUnits(assetId)
            // Persisted BEFORE the intent leaves: once submitted it may run, and a crash must resume polling it.
            val checkpoint =
                InvestBuyCheckpoint(
                    depositAddress = depositAddress,
                    assetId = assetId,
                    createdAtMillis = now().toEpochMilliseconds(),
                    intentDeadlineMillis = prepared.intentDeadline.toEpochMilliseconds(),
                    baseUnits = prepared.baseUnits,
                    heldBeforeBaseUnits = heldBefore.toString(),
                )
            checkpoints.add(checkpoint)
            submit(checkpoint, signed)
        } finally {
            withContext(NonCancellable) { submittingMutex.withLock { submitting -= assetId } }
        }
        return depositAddress
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
     * Submits once, and never re-signs. Only a failure that proves nothing was accepted (Tor that didn't start,
     * a rejected partner token, rate limiting, or a refused signature or deadline) drops the checkpoint and
     * reaches the caller. Anything else may mean the intent was accepted: the checkpoint stays and polling
     * finds out, with the signed intent's own deadline ending the wait if it never ran.
     */
    private suspend fun submit(
        checkpoint: InvestBuyCheckpoint,
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
            checkpoints.add(checkpoint.copy(intentHash = response.intentHash))
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

    override fun observeSell(depositAddress: String): Flow<SellProgress> =
        flow {
            val intentDeadline = checkpoint(depositAddress)?.intentDeadlineMillis?.let(Instant::fromEpochMilliseconds)
            val poll = Poll(depositAddress, intentDeadline)
            while (true) {
                val progress = poll.next()
                if (progress != null) {
                    if (progress.isFinal) settle(progress)
                    emit(progress)
                    if (progress.isFinal) break
                }
                delay(pollIntervalMillis)
            }
        }

    private class Poll(
        val depositAddress: String,
        val intentDeadline: Instant?,
    ) {
        var rejected = 0
    }

    /** One status check; null when there is nothing new to report yet. */
    private suspend fun Poll.next(): SellProgress? =
        try {
            toProgress(api.checkStatus(depositAddress), depositAddress, intentDeadline).also { rejected = 0 }
        } catch (e: CancellationException) {
            throw e
        } catch (e: InvestApiException.Api) {
            onRejected(e)
        } catch (_: InvestApiException) {
            null
        }

    private suspend fun Poll.onRejected(e: InvestApiException.Api): SellProgress? {
        // Status may not know the quote until its intent is seen; before the deadline that's still waiting.
        val waiting = e.status == HTTP_NOT_FOUND && (intentDeadline == null || now() < intentDeadline)
        rejected = if (!waiting && e.status in PERMANENT_POLL_ERRORS) rejected + 1 else 0
        return when {
            waiting -> SellProgress.Authorised(depositAddress)
            rejected >= MAX_REJECTED_POLLS -> needsAttention(depositAddress)
            else -> null
        }
    }

    override suspend fun dismissSell(depositAddress: String) {
        checkpoints.remove(depositAddress)
    }

    private suspend fun settle(progress: SellProgress) {
        if (progress !is SellProgress.NeedsAttention) checkpoints.remove(progress.depositAddress)
        try {
            investRepository.refreshHoldings()
        } catch (e: CancellationException) {
            throw e
        } catch (_: InvestApiException) {
            // The holdings catch up on the next refresh.
        }
    }

    private suspend fun toProgress(
        status: SwapStatusResponseDto,
        depositAddress: String,
        intentDeadline: Instant?,
    ): SellProgress? =
        when (status.status) {
            SwapStatus.PENDING_DEPOSIT -> {
                if (intentDeadline != null && now() > intentDeadline + NOT_SOLD_GRACE) {
                    notSoldOrAttention(depositAddress)
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
                needsAttention(depositAddress)
            }

            null -> {
                null
            }
        }

    /**
     * Past the signed intent's deadline it can't run any more, but 1Click not having seen it doesn't prove it
     * never ran (near/intents#356). The stock counts as unsold only while the private balance still holds it.
     * A balance that can't be read now is asked for again on the next poll. Each early return is a case that
     * can't be settled as unsold: no record to check against, or no balance yet.
     */
    @Suppress("ReturnCount")
    private suspend fun notSoldOrAttention(depositAddress: String): SellProgress {
        val checkpoint = checkpoint(depositAddress)
        val before = checkpoint?.heldBeforeBaseUnits?.toBigIntegerOrNull()
        val sold = checkpoint?.baseUnits?.toBigIntegerOrNull()
        if (checkpoint == null || before == null || sold == null) return needsAttention(depositAddress)
        val held =
            try {
                heldBaseUnits(checkpoint.assetId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: InvestApiException) {
                return SellProgress.Authorised(depositAddress)
            }
        return if (held > before - sold) SellProgress.NotSold(depositAddress) else needsAttention(depositAddress)
    }

    /** The intent hash is what 1Click support can look up; the deposit address stands in until one is known. */
    private suspend fun needsAttention(depositAddress: String) =
        SellProgress.NeedsAttention(
            depositAddress = depositAddress,
            reference = checkpoint(depositAddress)?.intentHash ?: depositAddress,
        )

    private suspend fun checkpoint(depositAddress: String): InvestBuyCheckpoint? =
        checkpoints.observe().first().firstOrNull { it.depositAddress == depositAddress }

    private suspend fun hasSaleInFlight(asset: InvestAsset): Boolean =
        checkpoints.observe().first().any { it.assetId == asset.assetId }

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
                // Priced without a price: the quote's own USD value is then held to the minimum.
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

    /** What the private account holds of [assetId]; the balances may list other sources, which can't be sold. */
    private suspend fun heldBaseUnits(assetId: String): BigInteger =
        session
            .balances()
            .balances
            .filter { it.tokenId == assetId && (it.source == null || it.source == PRIVATE_SOURCE) }
            .mapNotNull { it.available.toBigIntegerOrNull() }
            .fold(BigInteger.ZERO, BigInteger::add)

    private fun expected(
        depositAddress: String,
        asset: InvestAsset,
        baseUnits: String,
    ) = ExpectedTransfer(receiverId = depositAddress, tokenId = asset.assetId, amount = baseUnits)

    private fun quoteRequest(
        dry: Boolean,
        asset: InvestAsset,
        baseUnits: BigInteger,
        recipient: String,
        accountId: String,
    ) = QuoteRequest(
        dry = dry,
        swapType = SwapType.EXACT_INPUT,
        slippageTolerance = InvestSellChecks.SLIPPAGE_BPS,
        originAsset = asset.assetId,
        depositType = RefundType.CONFIDENTIAL_INTENTS,
        destinationAsset = InvestSellChecks.ZEC_ASSET_ID,
        amount = BigDecimal(baseUnits),
        refundTo = accountId,
        refundType = RefundType.CONFIDENTIAL_INTENTS,
        recipient = recipient,
        recipientType = RecipientType.DESTINATION_CHAIN,
        deadline = now() + QUOTE_DEADLINE,
        quoteWaitingTimeMs = QUOTE_WAITING_TIME_MS,
        appFees = emptyList(),
        referral = REFERRAL,
        confidentiality = Confidentiality.BASIC,
    )

    private fun payloadDeadline(payload: String): Instant {
        val deadline = ((Json.parseToJsonElement(payload) as JsonObject)["deadline"] as JsonPrimitive).content
        return Instant.parse(deadline)
    }

    private fun InvestApiException.Api.isAmountTooLow() =
        status == HTTP_BAD_REQUEST && apiMessage.orEmpty().contains(TOO_LOW, ignoreCase = true)

    private companion object {
        const val STANDARD = "erc191"
        const val PRIVATE_SOURCE = "private"
        const val ZEC_DECIMALS = 8
        const val QUOTE_WAITING_TIME_MS = 3_000
        const val REFERRAL = "zapp"
        const val TOO_LOW = "too low"
        const val SALE_IN_FLIGHT = "A sale of this stock is still in progress"
        const val PRICE_EXPIRED = "The price is no longer held; prepare the sale again"
        const val HTTP_BAD_REQUEST = 400
        const val HTTP_NOT_FOUND = 404
        const val DEFAULT_POLL_INTERVAL_MS = 5_000L
        const val MAX_REJECTED_POLLS = 3
        val PERMANENT_POLL_ERRORS = setOf(400, 404, 410)
        val PRICE_HELD = 10.minutes
        val PRICE_MAX_AGE = 1.minutes
        val SUBMIT_MARGIN = 1.minutes
        val QUOTE_DEADLINE = 30.minutes

        // A signed intent past its deadline can't run; this covers clock differences before checking the balance.
        val NOT_SOLD_GRACE = 5.minutes
    }
}
