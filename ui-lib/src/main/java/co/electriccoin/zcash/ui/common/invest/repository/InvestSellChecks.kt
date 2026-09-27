package co.electriccoin.zcash.ui.common.invest.repository

import co.electriccoin.zcash.ui.common.invest.model.InvestAsset
import co.electriccoin.zcash.ui.common.invest.model.SellAmount
import co.electriccoin.zcash.ui.common.invest.model.SellEstimate
import co.electriccoin.zcash.ui.common.model.near.Confidentiality
import co.electriccoin.zcash.ui.common.model.near.QuoteRequest
import co.electriccoin.zcash.ui.common.model.near.QuoteResponseDto
import co.electriccoin.zcash.ui.common.model.near.RecipientType
import co.electriccoin.zcash.ui.common.model.near.RefundType
import co.electriccoin.zcash.ui.common.model.near.SwapType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** The pure decisions behind a sale, kept apart from the network calls so each can be tested on its own. */
internal object InvestSellChecks {
    const val ZEC_ASSET_ID = "nep141:zec.omft.near"
    const val SLIPPAGE_BPS = 100
    private val MAX_USD_DRIFT = BigDecimal("0.05")
    private val SLIPPAGE_FLOOR = BigDecimal("0.99")

    // A partial sale must return at least this share of its value in ZEC; fees measured 3.7 % at $30 and
    // 0.5 % at $500 on 2026-09-25, so this only catches a quote that is plainly wrong.
    private val MIN_PARTIAL_RETURN = BigDecimal("0.9")

    sealed interface Resolved {
        data class Order(
            val baseUnits: BigInteger,
            val units: BigDecimal,
            val usdValue: BigDecimal?,
            val isAll: Boolean,
        ) : Resolved

        data class Refused(
            val estimate: SellEstimate,
        ) : Resolved
    }

    /**
     * The amount to sell in base units, or why there is nothing to quote. A partial sale keeps the minimum,
     * measured on what the user asked for; selling everything is always allowed.
     */
    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    fun resolve(
        amount: SellAmount,
        heldBaseUnits: BigInteger,
        decimals: Int,
        unitPrice: BigDecimal?,
    ): Resolved {
        if (heldBaseUnits.signum() <= 0) return Resolved.Refused(SellEstimate.NothingHeld)
        val price = unitPrice?.takeIf { it.signum() > 0 }
        val baseUnits =
            when (amount) {
                SellAmount.All -> {
                    heldBaseUnits
                }

                is SellAmount.Units -> {
                    amount.units.toBaseUnits(decimals)
                }

                is SellAmount.Usd -> {
                    price ?: return Resolved.Refused(SellEstimate.NoPrice)
                    amount.value.divide(price, decimals, RoundingMode.DOWN).toBaseUnits(decimals)
                }
            }
        val units = BigDecimal(baseUnits).movePointLeft(decimals)
        val value = price?.let { units.multiply(it) }
        val askedUsd = (amount as? SellAmount.Usd)?.value ?: value
        return when {
            baseUnits > heldBaseUnits -> {
                Resolved.Refused(SellEstimate.ExceedsHolding(BigDecimal(heldBaseUnits).movePointLeft(decimals)))
            }

            baseUnits.signum() <= 0 -> {
                Resolved.Refused(SellEstimate.BelowMinimum(InvestRepository.MINIMUM_USD))
            }

            amount != SellAmount.All && askedUsd != null && askedUsd < InvestRepository.MINIMUM_USD -> {
                Resolved.Refused(SellEstimate.BelowMinimum(InvestRepository.MINIMUM_USD))
            }

            else -> {
                Resolved.Order(baseUnits = baseUnits, units = units, usdValue = value, isAll = amount == SellAmount.All)
            }
        }
    }

    /** Throws unless the live quote is for exactly this sale. */
    @Suppress("CyclomaticComplexMethod")
    fun requireEcho(
        response: QuoteResponseDto,
        asset: InvestAsset,
        order: Resolved.Order,
        accountId: String,
        recipient: String,
    ) {
        val echo = response.quoteRequest
        val quote = response.quote
        require(!echo.dry) { "Sell quote echo: dry" }
        require(echo.swapType == SwapType.EXACT_INPUT) { "Sell quote echo: swapType" }
        require(echo.slippageTolerance == SLIPPAGE_BPS) { "Sell quote echo: slippageTolerance" }
        require(echo.originAsset == asset.assetId) { "Sell quote echo: originAsset" }
        require(echo.destinationAsset == ZEC_ASSET_ID) { "Sell quote echo: destinationAsset" }
        require(echo.depositType == RefundType.CONFIDENTIAL_INTENTS) { "Sell quote echo: depositType" }
        require(echo.refundType == RefundType.CONFIDENTIAL_INTENTS) { "Sell quote echo: refundType" }
        require(echo.refundTo == accountId) { "Sell quote echo: refundTo" }
        require(echo.recipient == recipient) { "Sell quote echo: recipient" }
        require(echo.recipientType == RecipientType.DESTINATION_CHAIN) { "Sell quote echo: recipientType" }
        require(quote.amountIn.compareTo(BigDecimal(order.baseUnits)) == 0) { "Sell quote echo: amountIn" }
        // The ZEC side: the floor the user is promised must really be within the 1 % slippage asked for.
        // 1Click rounds its floor down to a whole zat (6342045 out, 6278624 floor on 2026-09-25).
        require(quote.minAmountOut >= quote.amountOut.multiply(SLIPPAGE_FLOOR).setScale(0, RoundingMode.DOWN)) {
            "Sell quote: minAmountOut"
        }
        order.usdValue?.let { value ->
            require((quote.amountInUsd - value).abs() <= value.multiply(MAX_USD_DRIFT)) {
                "Sell quote is priced too far from the holding's value"
            }
        }
        if (!order.isAll) {
            // With a price the minimum was already held to what the user asked; rounding down to base units
            // can leave the quote a fraction of a cent under it. Without one, the quote is all there is.
            require(!isBelowMinimum(order, quote.amountInUsd)) { "Sell quote is below the minimum" }
            require(quote.amountOutUsd >= quote.amountInUsd.multiply(MIN_PARTIAL_RETURN)) {
                "Sell quote returns too little ZEC for the stock's value"
            }
        }
    }

    /**
     * A partial sale resolved without a price, whose quote turns out worth less than the minimum. With a price
     * the minimum was already held to what the user asked, where rounding down to base units can leave the
     * quote a fraction of a cent under it.
     */
    fun isBelowMinimum(
        order: Resolved.Order,
        quotedUsd: BigDecimal,
    ): Boolean = !order.isAll && order.usdValue == null && quotedUsd < InvestRepository.MINIMUM_USD

    /** 1Click refusing to quote an amount too small to cover its fees. */
    fun isAmountTooSmall(
        status: Int?,
        message: String?,
    ): Boolean {
        val text = message.orEmpty().lowercase()
        return status == HTTP_BAD_REQUEST && TOO_SMALL.any { it in text }
    }

    /**
     * Whether a submit-intent failure proves nothing was accepted. Only then may the checkpoint go: a replayed
     * or half-seen submission can come back as almost anything, and a kept checkpoint costs at most a wait
     * until the signed intent's deadline.
     */
    fun isDefiniteRefusal(
        status: Int?,
        message: String?,
    ): Boolean {
        val text = message.orEmpty().lowercase()
        return status == HTTP_TOO_MANY_REQUESTS ||
            (status == HTTP_BAD_REQUEST && DEFINITE_REFUSALS.any { it in text } && !REPLAYED.containsMatchIn(text))
    }

    /** The quote for selling [baseUnits] of [asset] from the private account to [recipient]'s shielded address. */
    fun quoteRequest(
        dry: Boolean,
        asset: InvestAsset,
        baseUnits: BigInteger,
        recipient: String,
        accountId: String,
        now: Instant,
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
        deadline = now + QUOTE_DEADLINE,
        quoteWaitingTimeMs = QUOTE_WAITING_TIME_MS,
        appFees = emptyList(),
        referral = REFERRAL,
        confidentiality = Confidentiality.BASIC,
    )

    /** The `deadline` of a generated intent, which [xyz.justzappit.evm.intents.IntentTransferSigner] checked. */
    fun payloadDeadline(payload: String): Instant {
        val deadline = ((Json.parseToJsonElement(payload) as JsonObject)["deadline"] as JsonPrimitive).content
        return Instant.parse(deadline)
    }

    private fun BigDecimal.toBaseUnits(decimals: Int): BigInteger =
        movePointRight(decimals).setScale(0, RoundingMode.DOWN).toBigInteger()

    private const val QUOTE_WAITING_TIME_MS = 3_000
    private const val REFERRAL = "zapp"
    private val QUOTE_DEADLINE = 30.minutes
    private const val HTTP_BAD_REQUEST = 400
    private const val HTTP_TOO_MANY_REQUESTS = 429

    // Not "balance" or "insufficient": that is exactly what a replay says after the first submission sold
    // the stock. A replay of the same signed payload can't fail on its signature or, seconds later, its deadline.
    private val DEFINITE_REFUSALS = listOf("signature", "deadline")

    // What a replay of an accepted intent may say ("nonce already used", "duplicate signature"): not definite.
    // Word stems, so "duplicated" and "reused" read as a replay but "refused" and "nonexistent" don't.
    private val REPLAYED = Regex("\\b(nonce|already|duplicat\\w*|(re)?used|exist\\w*)\\b")

    // "Quote error. INSUFFICIENT_AMOUNT" is what 1Click answered for an unsellable amount on 2026-09-25.
    private val TOO_SMALL = listOf("insufficient_amount", "too low")
}
