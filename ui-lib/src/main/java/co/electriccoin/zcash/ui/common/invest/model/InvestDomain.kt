package co.electriccoin.zcash.ui.common.invest.model

import co.electriccoin.zcash.ui.common.model.SwapQuote
import java.math.BigDecimal
import kotlin.time.Instant

data class MarketAsset(
    val asset: InvestAsset,
    /** Null when 1Click lists no price. */
    val usdPrice: BigDecimal?,
)

data class InvestMarket(
    val assets: List<MarketAsset>,
    val updatedAt: Instant,
)

data class Holding(
    val asset: InvestAsset,
    /** Whole shares. */
    val units: BigDecimal,
    val usdValue: BigDecimal?,
)

data class Holdings(
    val items: List<Holding>,
    /** Sum of the priced holdings; null when none is priced. */
    val totalUsd: BigDecimal?,
    /** On this phone's clock, only for "Updated 3 minutes ago". */
    val updatedAt: Instant,
    /** The last refresh failed and these are the last known figures. */
    val isStale: Boolean,
)

sealed interface BuyEstimate {
    data class Priced(
        val zecIn: BigDecimal,
        val unitsOut: BigDecimal,
        val usdOut: BigDecimal,
        val feesUsd: BigDecimal,
        val etaSeconds: Int?,
        /** ZEC kept from a refund if the buy can't complete. */
        val refundFeeZec: BigDecimal?,
    ) : BuyEstimate

    data object NoPrice : BuyEstimate

    data class BelowMinimum(
        val minimumUsd: BigDecimal,
    ) : BuyEstimate

    data class InsufficientZec(
        val spendableZec: BigDecimal,
    ) : BuyEstimate
}

/** A live quote, checked against the request, for the review sheet. */
data class PreparedBuy(
    val asset: InvestAsset,
    val zecIn: BigDecimal,
    val unitsOutExpected: BigDecimal,
    val unitsOutMin: BigDecimal,
    val usdOut: BigDecimal,
    val feesUsd: BigDecimal,
    val refundFeeZec: BigDecimal?,
    val etaSeconds: Int?,
    /** When the price stops being held. */
    val expiresAt: Instant,
    internal val quote: SwapQuote,
) {
    val depositAddress: String get() = quote.depositAddress.address
}

sealed interface BuyProgress {
    val depositAddress: String

    data class SendingZec(
        override val depositAddress: String,
    ) : BuyProgress

    data class PaymentReceived(
        override val depositAddress: String,
        /** 1Click saw less ZEC than quoted; it will complete or refund. */
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

    /** No deposit reached 1Click before the quote's deadline; late ZEC is refunded. */
    data class Expired(
        override val depositAddress: String,
    ) : BuyProgress

    data class NeedsAttention(
        override val depositAddress: String,
        val reference: String,
    ) : BuyProgress

    val isFinal: Boolean get() = this is Held || this is Refunded || this is Expired || this is NeedsAttention
}

/** A buy or sale not yet final, stuck ones included until dismissed. */
data class PendingTrade(
    val depositAddress: String,
    val assetId: String,
    val isSale: Boolean,
)
