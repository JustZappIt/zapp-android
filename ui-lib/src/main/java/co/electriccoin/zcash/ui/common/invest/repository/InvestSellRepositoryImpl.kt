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
import co.electriccoin.zcash.ui.common.invest.provider.InvestBuyCheckpoint
import co.electriccoin.zcash.ui.common.invest.provider.InvestSellCheckpointStorageProvider
import co.electriccoin.zcash.ui.common.invest.provider.PrivateAccountKeyProvider
import co.electriccoin.zcash.ui.common.invest.provider.PrivateAccountSession
import co.electriccoin.zcash.ui.common.model.near.Confidentiality
import co.electriccoin.zcash.ui.common.model.near.QuoteRequest
import co.electriccoin.zcash.ui.common.model.near.QuoteResponseDto
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
import java.math.RoundingMode
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Sells a holding back to ZEC. The quote's deposit comes from the private account and the refund goes back
 * to it; the ZEC is paid to a fresh shielded address in this wallet. The intent `generate-intent` returns
 * is checked by [IntentTransferSigner] before it is shown, and again as it is signed.
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
                api.requestQuote(
                    quoteRequest(
                        dry = true,
                        asset = asset,
                        baseUnits = order.baseUnits,
                        recipient = recipient,
                        accountId = keys.accountId(),
                    ),
                )
            } catch (_: InvestApiException.NoPrice) {
                return SellEstimate.NoPrice
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
        val order =
            when (val resolved = resolve(asset, amount)) {
                is Resolved.Order -> resolved
                is Resolved.Refused -> error("Can't sell: ${resolved.estimate}")
            }
        val accountId = keys.accountId()
        // A fresh shielded address per sale, so the payout isn't linked to any other payment.
        val recipient = wallet.zcashAddress()
        val response =
            api.requestQuote(
                quoteRequest(
                    dry = false,
                    asset = asset,
                    baseUnits = order.baseUnits,
                    recipient = recipient,
                    accountId = accountId,
                ),
            )
        requireEcho(response, asset, order, accountId, recipient)
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
        val expected =
            ExpectedTransfer(receiverId = depositAddress, tokenId = asset.assetId, amount = order.baseUnits.toString())
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
        check(now() < prepared.expiresAt) { "The price is no longer held; prepare the sale again" }
        check(investRepository.isAccountSupported()) { "Invest isn't available for this account" }
        val depositAddress = prepared.depositAddress
        submittingMutex.withLock {
            check(depositAddress !in submitting) { "This sale is already being submitted" }
            check(checkpoints.observe().first().none { it.depositAddress == depositAddress }) {
                "This sale was already submitted"
            }
            submitting += depositAddress
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
            val expected =
                ExpectedTransfer(
                    receiverId = depositAddress,
                    tokenId = prepared.asset.assetId,
                    amount = prepared.baseUnits,
                )
            val signed =
                keys.withKey { key ->
                    IntentTransferSigner.sign(key, prepared.signedMessage, expected, now().toEpochMilliseconds())
                }
            val signature =
                when (signed) {
                    is IntentTransferSigner.SignResult.Signed -> signed
                    is IntentTransferSigner.SignResult.Refused -> throw SellIntentRefusedException(signed.reason, null)
                }
            // Persisted BEFORE the intent leaves: once submitted it may run, and a crash must resume polling it.
            checkpoints.add(
                InvestBuyCheckpoint(
                    depositAddress = depositAddress,
                    assetId = prepared.asset.assetId,
                    createdAtMillis = now().toEpochMilliseconds(),
                    intentDeadlineMillis = prepared.intentDeadline.toEpochMilliseconds(),
                ),
            )
            submit(depositAddress, signature)
        } finally {
            withContext(NonCancellable) { submittingMutex.withLock { submitting -= depositAddress } }
        }
        return depositAddress
    }

    /**
     * Submits once. A refusal 1Click is sure of drops the checkpoint and reaches the caller. A used nonce, a 5xx
     * or no answer at all may mean the intent was accepted, so those keep the checkpoint: polling then tells
     * whether it ran, and the intent's own deadline ends the wait if it never did. Nothing here re-signs.
     */
    private suspend fun submit(
        depositAddress: String,
        signed: IntentTransferSigner.SignResult.Signed,
    ) {
        try {
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
        } catch (e: InvestApiException.Api) {
            val maybeAccepted =
                e.status >= HTTP_SERVER_ERROR || e.apiMessage.orEmpty().contains(NONCE, ignoreCase = true)
            if (!maybeAccepted) {
                checkpoints.remove(depositAddress)
                throw e
            }
        } catch (_: InvestApiException.Unreachable) {
            // Keep the checkpoint and let polling decide.
        }
    }

    override fun observeSell(depositAddress: String): Flow<SellProgress> =
        flow {
            val intentDeadline =
                checkpoints
                    .observe()
                    .first()
                    .firstOrNull { it.depositAddress == depositAddress }
                    ?.intentDeadlineMillis
                    ?.let(Instant::fromEpochMilliseconds)
            var rejectedPolls = 0
            while (true) {
                val progress =
                    try {
                        api
                            .checkStatus(depositAddress)
                            .toProgress(depositAddress, intentDeadline)
                            .also { rejectedPolls = 0 }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: InvestApiException.Api) {
                        rejectedPolls = if (e.status in PERMANENT_POLL_ERRORS) rejectedPolls + 1 else 0
                        if (rejectedPolls >= MAX_REJECTED_POLLS) {
                            SellProgress.NeedsAttention(depositAddress, reference = depositAddress)
                        } else {
                            null
                        }
                    } catch (_: InvestApiException) {
                        null
                    }
                if (progress != null) {
                    if (progress.isFinal) settle(progress)
                    emit(progress)
                    if (progress.isFinal) break
                }
                delay(pollIntervalMillis)
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

    private fun SwapStatusResponseDto.toProgress(
        depositAddress: String,
        intentDeadline: Instant?,
    ): SellProgress? =
        when (status) {
            SwapStatus.PENDING_DEPOSIT -> {
                // Past the signed intent's deadline it can no longer run: the stock never left the account.
                if (intentDeadline != null && now() > intentDeadline + NOT_SOLD_GRACE) {
                    SellProgress.NotSold(depositAddress)
                } else {
                    SellProgress.Authorised(depositAddress)
                }
            }

            SwapStatus.KNOWN_DEPOSIT_TX, SwapStatus.INCOMPLETE_DEPOSIT, SwapStatus.PROCESSING -> {
                SellProgress.Selling(depositAddress)
            }

            SwapStatus.SUCCESS -> {
                SellProgress.Sent(depositAddress, swapDetails?.amountOutFormatted)
            }

            SwapStatus.REFUNDED -> {
                SellProgress.ReturnedToAccount(depositAddress)
            }

            SwapStatus.FAILED -> {
                SellProgress.NeedsAttention(depositAddress, reference = depositAddress)
            }

            null -> {
                null
            }
        }

    /**
     * The amount to sell in base units, or why there is nothing to quote. Each early return is one such reason,
     * found before any quote is requested.
     */
    @Suppress("ReturnCount")
    private suspend fun resolve(
        asset: InvestAsset,
        amount: SellAmount,
    ): Resolved {
        val decimals =
            requireNotNull(swapAssets.investSwapAssets().firstOrNull { it.assetId == asset.assetId }?.decimals) {
                "1Click no longer lists this stock"
            }
        val held =
            session
                .balances()
                .balances
                .firstOrNull { it.tokenId == asset.assetId }
                ?.available
                ?.toBigIntegerOrNull()
                ?: BigInteger.ZERO
        if (held.signum() <= 0) return Resolved.Refused(SellEstimate.NothingHeld)
        val price =
            investRepository.market.value
                ?.assets
                ?.firstOrNull { it.asset == asset }
                ?.usdPrice
        val baseUnits =
            when (amount) {
                SellAmount.All -> {
                    held
                }

                is SellAmount.Units -> {
                    amount.units
                        .movePointRight(decimals)
                        .setScale(0, RoundingMode.DOWN)
                        .toBigInteger()
                }

                is SellAmount.Usd -> {
                    val unitPrice = price?.takeIf { it.signum() > 0 } ?: return Resolved.Refused(SellEstimate.NoPrice)
                    amount.value
                        .divide(unitPrice, decimals, RoundingMode.DOWN)
                        .movePointRight(decimals)
                        .setScale(0, RoundingMode.DOWN)
                        .toBigInteger()
                }
            }
        val units = BigDecimal(baseUnits).movePointLeft(decimals)
        return when {
            baseUnits.signum() <= 0 -> {
                Resolved.Refused(SellEstimate.BelowMinimum(InvestRepository.MINIMUM_USD))
            }

            baseUnits > held -> {
                Resolved.Refused(SellEstimate.ExceedsHolding(BigDecimal(held).movePointLeft(decimals)))
            }

            // Selling everything is always allowed; a partial sale keeps the minimum, as fixed fees dominate below it.
            amount != SellAmount.All && price != null && units.multiply(price) < InvestRepository.MINIMUM_USD -> {
                Resolved.Refused(SellEstimate.BelowMinimum(InvestRepository.MINIMUM_USD))
            }

            else -> {
                Resolved.Order(baseUnits = baseUnits, units = units, usdValue = price?.let { units.multiply(it) })
            }
        }
    }

    private fun quoteRequest(
        dry: Boolean,
        asset: InvestAsset,
        baseUnits: BigInteger,
        recipient: String,
        accountId: String,
    ) = QuoteRequest(
        dry = dry,
        swapType = SwapType.EXACT_INPUT,
        slippageTolerance = SLIPPAGE_BPS,
        originAsset = asset.assetId,
        depositType = RefundType.CONFIDENTIAL_INTENTS,
        destinationAsset = ZEC_ASSET_ID,
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

    private fun requireEcho(
        response: QuoteResponseDto,
        asset: InvestAsset,
        order: Resolved.Order,
        accountId: String,
        recipient: String,
    ) {
        val echo = response.quoteRequest
        require(!echo.dry) { "Sell quote echo: dry" }
        require(echo.swapType == SwapType.EXACT_INPUT) { "Sell quote echo: swapType" }
        require(echo.originAsset == asset.assetId) { "Sell quote echo: originAsset" }
        require(echo.destinationAsset == ZEC_ASSET_ID) { "Sell quote echo: destinationAsset" }
        require(echo.depositType == RefundType.CONFIDENTIAL_INTENTS) { "Sell quote echo: depositType" }
        require(echo.refundType == RefundType.CONFIDENTIAL_INTENTS) { "Sell quote echo: refundType" }
        require(echo.refundTo == accountId) { "Sell quote echo: refundTo" }
        require(echo.recipient == recipient) { "Sell quote echo: recipient" }
        require(echo.recipientType == RecipientType.DESTINATION_CHAIN) { "Sell quote echo: recipientType" }
        require(response.quote.amountIn.compareTo(BigDecimal(order.baseUnits)) == 0) { "Sell quote echo: amountIn" }
        order.usdValue?.let { value ->
            require((response.quote.amountInUsd - value).abs() <= value.multiply(MAX_USD_DRIFT)) {
                "Sell quote is priced too far from the holding's value"
            }
        }
    }

    private fun payloadDeadline(payload: String): Instant {
        val deadline = ((Json.parseToJsonElement(payload) as JsonObject)["deadline"] as JsonPrimitive).content
        return Instant.parse(deadline)
    }

    private sealed interface Resolved {
        data class Order(
            val baseUnits: BigInteger,
            val units: BigDecimal,
            val usdValue: BigDecimal?,
        ) : Resolved

        data class Refused(
            val estimate: SellEstimate,
        ) : Resolved
    }

    private companion object {
        const val STANDARD = "erc191"
        const val ZEC_ASSET_ID = "nep141:zec.omft.near"
        const val ZEC_DECIMALS = 8
        const val SLIPPAGE_BPS = 100
        const val QUOTE_WAITING_TIME_MS = 3_000
        const val REFERRAL = "zapp"
        const val NONCE = "nonce"
        const val HTTP_SERVER_ERROR = 500
        const val DEFAULT_POLL_INTERVAL_MS = 5_000L
        const val MAX_REJECTED_POLLS = 3
        val PERMANENT_POLL_ERRORS = setOf(400, 404, 410)
        val MAX_USD_DRIFT = BigDecimal("0.05")
        val PRICE_HELD = 10.minutes
        val SUBMIT_MARGIN = 1.minutes
        val QUOTE_DEADLINE = 30.minutes

        // A signed intent past its deadline can't run; this covers clock differences before calling it unsold.
        val NOT_SOLD_GRACE = 5.minutes
    }
}
