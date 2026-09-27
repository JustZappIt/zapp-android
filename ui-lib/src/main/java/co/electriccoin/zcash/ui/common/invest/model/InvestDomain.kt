package co.electriccoin.zcash.ui.common.invest.model

import co.electriccoin.zcash.ui.common.model.SwapQuote
import java.math.BigDecimal
import kotlin.time.Instant

/** One curated stock with its latest 1Click price in USD, or null when 1Click listed no price. */
data class MarketAsset(
    val asset: InvestAsset,
    val usdPrice: BigDecimal?,
)

data class InvestMarket(
    val assets: List<MarketAsset>,
    val updatedAt: Instant,
)

/** A position in the private account. [units] are whole shares (18-decimal token units already divided out). */
data class Holding(
    val asset: InvestAsset,
    val units: BigDecimal,
    /** [units] × the latest price, or null when there is no price. */
    val usdValue: BigDecimal?,
)

data class Holdings(
    val items: List<Holding>,
    /** Sum of the priced holdings; null when none is priced. */
    val totalUsd: BigDecimal?,
    val updatedAt: Instant,
    /** True when the last refresh failed and these are the last known figures. */
    val isStale: Boolean,
)

/** What the amount screen shows while the user types (a dry quote; nothing reserved, nothing sent). */
sealed interface BuyEstimate {
    data class Priced(
        val zecIn: BigDecimal,
        val unitsOut: BigDecimal,
        val usdOut: BigDecimal,
        /** Everything between the ZEC's USD value and [usdOut]: 1Click's 0.2 % plus price impact. */
        val feesUsd: BigDecimal,
        val etaSeconds: Int?,
        /** ZEC kept from a refund if the buy can't complete (32,000 zats on 2026-09-25/26). */
        val refundFeeZec: BigDecimal?,
    ) : BuyEstimate

    /** 1Click answered "No liquidity available": nobody is quoting this stock right now. */
    data object NoPrice : BuyEstimate

    data class BelowMinimum(
        val minimumUsd: BigDecimal,
    ) : BuyEstimate

    data class InsufficientZec(
        val spendableZec: BigDecimal,
    ) : BuyEstimate
}

/**
 * A live quote, checked against what was asked for and ready for the review sheet. [quote] carries the
 * deposit address the ZEC goes to; it is internal so only the repository can act on it.
 */
data class PreparedBuy(
    val asset: InvestAsset,
    val zecIn: BigDecimal,
    val unitsOutExpected: BigDecimal,
    /** The quote's `minAmountOut`: the least the buy can deliver within the 1 % slippage. */
    val unitsOutMin: BigDecimal,
    val usdOut: BigDecimal,
    val feesUsd: BigDecimal,
    val refundFeeZec: BigDecimal?,
    val etaSeconds: Int?,
    /** After this the price is no longer held and the review sheet must ask for a fresh one. */
    val expiresAt: Instant,
    internal val quote: SwapQuote,
)

/** Where a buy is, in the steps the progress screen shows. Terminal states end polling. */
sealed interface BuyProgress {
    val depositAddress: String

    data class SendingZec(
        override val depositAddress: String,
    ) : BuyProgress

    data class PaymentReceived(
        override val depositAddress: String,
        /** 1Click saw less ZEC than quoted; not final, it will complete or refund. */
        val incomplete: Boolean,
    ) : BuyProgress

    data class Buying(
        override val depositAddress: String,
    ) : BuyProgress

    data class Held(
        override val depositAddress: String,
        val units: BigDecimal?,
    ) : BuyProgress

    data class Refunded(
        override val depositAddress: String,
        val zec: BigDecimal?,
    ) : BuyProgress

    /** 1Click says FAILED. Support needs [reference] (the deposit address or correlation ID). */
    data class NeedsAttention(
        override val depositAddress: String,
        val reference: String,
    ) : BuyProgress

    val isFinal: Boolean get() = this is Held || this is Refunded || this is NeedsAttention
}
