package co.electriccoin.zcash.ui.common.invest.model

import xyz.justzappit.evm.intents.IntentTransferSigner
import java.math.BigDecimal
import kotlin.time.Instant

/** How much of a holding to sell. */
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

/** What the sell screen shows while the user types (a dry quote; nothing signed, nothing sent). */
sealed interface SellEstimate {
    data class Priced(
        val unitsIn: BigDecimal,
        val usdIn: BigDecimal,
        val zecOut: BigDecimal,
        val usdOut: BigDecimal,
        /** 1Click's fee, price impact and the fixed ZEC withdrawal fee, in USD. */
        val feesUsd: BigDecimal,
        /** Fixed ZEC fee for the payout (64,000 zats on 2026-09-25). */
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
}

/**
 * A live sell quote and the intent `generate-intent` returned for it, already checked to move exactly [units]
 * of [asset] to the quote's deposit address and nothing else. Nothing is signed until `executeSell` runs.
 * Only the sell repository makes these, since `executeSell` trusts the fields it reads back.
 */
@ConsistentCopyVisibility
data class PreparedSell internal constructor(
    val asset: InvestAsset,
    val units: BigDecimal,
    val usdIn: BigDecimal,
    val zecOutExpected: BigDecimal,
    /** The quote's `minAmountOut` in ZEC: the least the sale can pay within the 1 % slippage. */
    val zecOutMin: BigDecimal,
    val feesUsd: BigDecimal,
    val withdrawFeeZec: BigDecimal?,
    val etaSeconds: Int?,
    val expiresAt: Instant,
    /** The exact message the key will sign, for "View signed message". */
    val signedMessage: String,
    internal val depositAddress: String,
    internal val baseUnits: String,
    internal val intentDeadline: Instant,
)

/** The generated intent wasn't what was reviewed, so nothing was shown or signed. */
class SellIntentRefusedException(
    val rejection: IntentTransferSigner.Rejection,
    val correlationId: String?,
) : IllegalStateException("The sell intent was refused: $rejection")

sealed interface SellProgress {
    val depositAddress: String

    /** Signed and submitted; waiting for NEAR Intents to execute it. */
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

    /** 1Click refunded the stock to the private account; nothing was sold. */
    data class ReturnedToAccount(
        override val depositAddress: String,
    ) : SellProgress

    /** The signed intent's deadline passed without it running, so the stock never left the account. */
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
