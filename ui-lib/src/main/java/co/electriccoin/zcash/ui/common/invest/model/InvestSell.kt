package co.electriccoin.zcash.ui.common.invest.model

import xyz.justzappit.evm.intents.IntentTransferSigner
import java.math.BigDecimal
import kotlin.time.Instant

sealed interface SellAmount {
    /** Everything in the private account, to the last base unit. */
    data object All : SellAmount

    data class Usd(
        val value: BigDecimal,
    ) : SellAmount

    data class Units(
        val units: BigDecimal,
    ) : SellAmount
}

sealed interface SellEstimate {
    data class Priced(
        val unitsIn: BigDecimal,
        val usdIn: BigDecimal,
        val zecOut: BigDecimal,
        val usdOut: BigDecimal,
        val feesUsd: BigDecimal,
        /** Fixed ZEC fee for the payout. */
        val withdrawFeeZec: BigDecimal?,
        val etaSeconds: Int?,
    ) : SellEstimate

    data object NoPrice : SellEstimate

    data class BelowMinimum(
        val minimumUsd: BigDecimal,
    ) : SellEstimate

    data class ExceedsHolding(
        val heldUnits: BigDecimal,
    ) : SellEstimate

    data object NothingHeld : SellEstimate

    /** Selling everything, but the holding is worth less than 1Click's fixed fees. */
    data object TooSmallToSell : SellEstimate
}

/** A live sell quote and its generated intent, already checked to be exactly this sale. */
@ConsistentCopyVisibility
data class PreparedSell internal constructor(
    val asset: InvestAsset,
    val units: BigDecimal,
    val usdIn: BigDecimal,
    val zecOutExpected: BigDecimal,
    val zecOutMin: BigDecimal,
    val feesUsd: BigDecimal,
    val withdrawFeeZec: BigDecimal?,
    val etaSeconds: Int?,
    val expiresAt: Instant,
    /** The exact message the key will sign. */
    val signedMessage: String,
    internal val depositAddress: String,
    internal val baseUnits: String,
    internal val intentDeadline: Instant,
)

class SellIntentRefusedException(
    val rejection: IntentTransferSigner.Rejection,
    val correlationId: String?,
) : IllegalStateException("The sell intent was refused: $rejection")

sealed interface SellProgress {
    val depositAddress: String

    /** Signed and submitted. */
    data class Authorised(
        override val depositAddress: String,
    ) : SellProgress

    data class Selling(
        override val depositAddress: String,
    ) : SellProgress

    data class Sent(
        override val depositAddress: String,
        val zec: BigDecimal?,
    ) : SellProgress

    /** 1Click refunded the stock to the private account. */
    data class ReturnedToAccount(
        override val depositAddress: String,
    ) : SellProgress

    /** The signed intent's deadline passed without it running. */
    data class NotSold(
        override val depositAddress: String,
    ) : SellProgress

    data class NeedsAttention(
        override val depositAddress: String,
        val reference: String,
    ) : SellProgress

    val isFinal: Boolean
        get() = this is Sent || this is ReturnedToAccount || this is NotSold || this is NeedsAttention
}
