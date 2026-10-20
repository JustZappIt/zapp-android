package co.electriccoin.zcash.ui.common.invest.repository

import co.electriccoin.zcash.ui.common.invest.model.InvestAsset
import co.electriccoin.zcash.ui.common.invest.model.SellAmount
import co.electriccoin.zcash.ui.common.invest.model.SellEstimate
import co.electriccoin.zcash.ui.common.invest.repository.InvestQuoteParams.QUOTE_WAITING_TIME_MS
import co.electriccoin.zcash.ui.common.invest.repository.InvestQuoteParams.REFERRAL
import co.electriccoin.zcash.ui.common.invest.repository.InvestQuoteParams.SLIPPAGE_BPS
import co.electriccoin.zcash.ui.common.invest.repository.InvestQuoteParams.ZEC_ASSET_ID
import co.electriccoin.zcash.ui.common.model.near.Confidentiality
import co.electriccoin.zcash.ui.common.model.near.QuoteRequest
import co.electriccoin.zcash.ui.common.model.near.QuoteResponseDto
import co.electriccoin.zcash.ui.common.model.near.RecipientType
import co.electriccoin.zcash.ui.common.model.near.RefundType
import co.electriccoin.zcash.ui.common.model.near.SwapType
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** The pure decisions behind a sale. */
internal object InvestSellChecks {
    private val MAX_USD_DRIFT = BigDecimal("0.05")
    private val SLIPPAGE_FLOOR = BigDecimal("0.99")

    // A partial sale returning less than this share of its value in ZEC is a plainly wrong quote.
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

    /** The amount to sell in base units, or why there's nothing to quote. Selling everything is always allowed. */
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
        require(echo.confidentiality == Confidentiality.BASIC) { "Sell quote echo: confidentiality" }
        require(quote.amountIn.compareTo(BigDecimal(order.baseUnits)) == 0) { "Sell quote echo: amountIn" }
        // 1Click rounds the floor down to a whole zat.
        require(quote.minAmountOut >= quote.amountOut.multiply(SLIPPAGE_FLOOR).setScale(0, RoundingMode.DOWN)) {
            "Sell quote: minAmountOut"
        }
        order.usdValue?.let { value ->
            require((quote.amountInUsd - value).abs() <= value.multiply(MAX_USD_DRIFT)) {
                "Sell quote is priced too far from the holding's value"
            }
        }
        if (!order.isAll) {
            require(!isBelowMinimum(order, quote.amountInUsd)) { "Sell quote is below the minimum" }
            require(quote.amountOutUsd >= quote.amountInUsd.multiply(MIN_PARTIAL_RETURN)) {
                "Sell quote returns too little ZEC for the stock's value"
            }
        }
    }

    /** A partial sale resolved without a price whose quote is worth less than the minimum. */
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

    /** Whether a submit-intent failure proves nothing was accepted; a replay can come back as almost anything. */
    fun isDefiniteRefusal(
        status: Int?,
        message: String?,
    ): Boolean {
        val text = message.orEmpty().lowercase()
        return status == HTTP_TOO_MANY_REQUESTS ||
            (status == HTTP_BAD_REQUEST && DEFINITE_REFUSALS.any { it in text } && !REPLAYED.containsMatchIn(text))
    }

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

    private fun BigDecimal.toBaseUnits(decimals: Int): BigInteger =
        movePointRight(decimals).setScale(0, RoundingMode.DOWN).toBigInteger()

    private val QUOTE_DEADLINE = 30.minutes
    private const val HTTP_BAD_REQUEST = 400
    private const val HTTP_TOO_MANY_REQUESTS = 429

    // Not "balance" or "insufficient": a replay says that once the first submission sold the stock.
    private val DEFINITE_REFUSALS = listOf("signature", "deadline")

    // What a replay of an accepted intent may say ("nonce already used", "duplicate signature").
    private val REPLAYED = Regex("\\b(nonce|already|duplicat\\w*|(re)?used|exist\\w*)\\b")

    private val TOO_SMALL = listOf("insufficient_amount", "too low")
}
