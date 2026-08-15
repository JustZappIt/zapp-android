// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.peer

import xyz.justzappit.evm.math.BigDecimal
import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.math.DecimalRounding
import xyz.justzappit.evm.math.bigDecimalFromBigInteger
import xyz.justzappit.evm.math.bigIntegerZero
import xyz.justzappit.evm.math.decimalMovePointLeft
import xyz.justzappit.evm.math.decimalSetScale
import xyz.justzappit.evm.math.decimalToPlainString
import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.offramp.p2p.Usdc6
import kotlin.jvm.JvmInline

/**
 * A fiat amount in the currency's minor units, which is how the indexer reports what a buyer owes
 * or has paid. Never computed by us: it is read from the intent's verified fields.
 */
@JvmInline
value class PeerFiat(
    val minor: BigInteger
) {
    fun toDisplayString(currency: PeerCurrency): String =
        decimalToPlainString(
            decimalSetScale(whole(currency), currency.precision, DecimalRounding.HALF_UP),
        )

    fun whole(currency: PeerCurrency): BigDecimal =
        decimalMovePointLeft(bigDecimalFromBigInteger(minor), currency.precision)

    companion object {
        val ZERO: PeerFiat = PeerFiat(bigIntegerZero)

        fun parseOrZero(raw: String?): PeerFiat =
            raw?.let { runCatching { PeerFiat(BigInteger(it)) }.getOrNull() } ?: ZERO
    }
}

/** `Deposit.status` is only ever these two. */
enum class PeerDepositStatus {
    ACTIVE,
    CLOSED,
    ;

    companion object {
        fun fromWireOrNull(raw: String?): PeerDepositStatus? =
            entries.firstOrNull { it.name == raw?.uppercase() }
    }
}

/**
 * All four are queried explicitly. Defaulting to SIGNALED silently hides delivered and returned
 * state, which reads as a stalled order on one that has already paid out.
 */
enum class PeerIntentStatus {
    SIGNALED,
    FULFILLED,
    PRUNED,
    MANUALLY_RELEASED,
    UNKNOWN,
    ;

    val isTerminal: Boolean get() = this == FULFILLED || this == PRUNED || this == MANUALLY_RELEASED

    val isPaidOut: Boolean get() = this == FULFILLED || this == MANUALLY_RELEASED

    companion object {
        fun fromWire(raw: String?): PeerIntentStatus =
            entries.firstOrNull { it.name == raw?.uppercase() } ?: UNKNOWN
    }
}

/** The escrow reports PRUNED for both a lock the buyer abandoned and one that ran out of time. */
enum class PeerIntentOutcome {
    PAYING,
    OUT_OF_TIME,
    PAID,
    BACKED_OUT,
    TIMED_OUT,
    UNKNOWN,
    ;

    val holdsFunds: Boolean get() = this == PAYING || this == OUT_OF_TIME

    val isPaidOut: Boolean get() = this == PAID
}

data class PeerIntent(
    val intentHash: String,
    val status: PeerIntentStatus,
    val amount: Usdc6,
    val releasedAmount: Usdc6,
    val conversionRate: Rate1e18?,
    val paymentCurrency: PeerCurrency?,
    val paymentAmount: PeerFiat,
    val paymentId: String?,
    val signalTimestampSeconds: Long?,
    val paymentTimestampSeconds: Long?,
    val fulfillTimestampSeconds: Long?,
    val pruneTimestampSeconds: Long?,
    val expiryTimeSeconds: Long?,
    val isExpired: Boolean,
    val fillLatencySeconds: Int?,
    val signalTxHash: TxHash?,
    val fulfillTxHash: TxHash?,
    val pruneTxHash: TxHash?,
) {
    val outcome: PeerIntentOutcome
        get() =
            when (status) {
                PeerIntentStatus.SIGNALED -> {
                    if (isExpired) PeerIntentOutcome.OUT_OF_TIME else PeerIntentOutcome.PAYING
                }

                PeerIntentStatus.FULFILLED,
                PeerIntentStatus.MANUALLY_RELEASED,
                -> {
                    PeerIntentOutcome.PAID
                }

                PeerIntentStatus.PRUNED -> {
                    if (ranOutOfTime) PeerIntentOutcome.TIMED_OUT else PeerIntentOutcome.BACKED_OUT
                }

                PeerIntentStatus.UNKNOWN -> {
                    PeerIntentOutcome.UNKNOWN
                }
            }

    fun secondsLeftToPay(nowSeconds: Long): Long? =
        expiryTimeSeconds
            ?.takeIf { outcome == PeerIntentOutcome.PAYING }
            ?.minus(nowSeconds)
            ?.takeIf { it > 0 }

    val heldForSeconds: Long?
        get() {
            val signal = signalTimestampSeconds ?: return null
            val prune = pruneTimestampSeconds ?: return null
            return (prune - signal).takeIf { it >= 0 }
        }

    val settlementTxHash: TxHash? get() = fulfillTxHash ?: pruneTxHash ?: signalTxHash

    private val ranOutOfTime: Boolean
        get() {
            val pruned = pruneTimestampSeconds ?: return isExpired
            val expiry = expiryTimeSeconds ?: return isExpired
            return pruned >= expiry
        }
}

data class PeerOrderCurrency(
    val currency: PeerCurrency?,
    val spread: Bps,
    val oracleRate: Rate1e18?,
    val lastOracleUpdatedAtSeconds: Long?,
)

/**
 * Where an order stands, decided once. Every surface that describes an order reads this instead of
 * re-testing the raw counters for itself.
 */
enum class PeerOrderPhase {
    WAITING,
    BUYER_PAYING,
    PARTLY_SOLD,
    SOLD,
    PAUSED,

    /** Nothing left on offer and nothing more will sell: what did not sell went back to Base. */
    CLOSED,
    ;

    val isFinished: Boolean get() = this == SOLD || this == CLOSED
}

/**
 * The whole waiting surface is derivable from this plus the deposit id, which is what makes the
 * order survive process death, reinstall, and a different device on the same seed.
 */
data class PeerOrderSnapshot(
    val id: PeerDepositId,
    val status: PeerDepositStatus,
    val acceptingIntents: Boolean,
    val remaining: Usdc6,
    val outstandingIntentAmount: Usdc6,
    val totalAmountTaken: Usdc6,
    val totalWithdrawn: Usdc6,
    val intentAmountMin: Usdc6,
    val intentAmountMax: Usdc6,
    val signaledIntents: Int,
    val fulfilledIntents: Int,
    val prunedIntents: Int,
    val platform: PeerPlatform?,
    val payeeHash: PayeeHash?,
    val currencies: List<PeerOrderCurrency>,
    val intents: List<PeerIntent>,
    val creationTxHash: TxHash?,
    val creationBlockNumber: Long?,
    val openedAtSeconds: Long?,
    val lastActivityAtSeconds: Long?,
    val totalIntents: Int,
) {
    /** Everything a buyer has taken and paid for. Historical: it never shrinks. */
    val soldAmount: Usdc6 get() = totalAmountTaken

    /**
     * The order at the size it was funded. The escrow moves a deposit between four counters and
     * never restates the original, so anything that describes how big the order is has to add them
     * back up — reading [remaining] alone makes an order visibly shrink the moment a buyer locks
     * part of it, and drops what a withdrawal already returned.
     */
    val grossAmount: Usdc6 get() = remaining + outstandingIntentAmount + totalAmountTaken + totalWithdrawn

    /**
     * A deposit no buyer can take a piece of, so it waits for one who wants the whole thing. The
     * escrow collapses the range this way whenever the order is posted at the intent floor.
     */
    val isAllOrNothing: Boolean get() = intentAmountMin > Usdc6.ZERO && intentAmountMin >= intentAmountMax

    /**
     * Still on offer as far as the escrow is concerned, and filtered out of the orderbook buyers
     * browse. Peer hides on either bound: the listing floor, or the order's own minimum.
     */
    val isHiddenFromBuyers: Boolean
        get() =
            remaining > Usdc6.ZERO &&
                (
                    remaining < Usdc6.ofMicros(PeerNetworks.ORDERBOOK_MIN_VISIBLE_MICROS) ||
                        remaining < intentAmountMin
                )

    val liveIntents: List<PeerIntent> get() = intents.filter { it.status == PeerIntentStatus.SIGNALED && !it.isExpired }

    val intentsNewestFirst: List<PeerIntent>
        get() =
            intents
                .filter { it.outcome != PeerIntentOutcome.UNKNOWN }
                .sortedByDescending { it.signalTimestampSeconds ?: 0L }

    val firstSignalSeconds: Long? get() = intents.mapNotNull { it.signalTimestampSeconds }.minOrNull()

    /** How long the order sat before any buyer took an interest. Null while none has. */
    val secondsToFirstBuyer: Long? get() = spanSeconds(openedAtSeconds, firstSignalSeconds)

    fun openForSeconds(nowSeconds: Long): Long? =
        spanSeconds(openedAtSeconds, if (phase.isFinished) lastActivityAtSeconds else nowSeconds)

    val hasExpiredIntentHoldingFunds: Boolean get() = expiredIntentAmount > Usdc6.ZERO

    /**
     * Held by intents that have already run out, which pruning releases back to the depositor.
     * Capped at the outstanding total: the intent list is a page of records and the counter is the
     * escrow's own arithmetic, so the counter is what bounds a claim about locked funds.
     */
    val expiredIntentAmount: Usdc6
        get() =
            minOf(
                outstandingIntentAmount,
                intents
                    .filter { it.status == PeerIntentStatus.SIGNALED && it.isExpired }
                    .fold(Usdc6.ZERO) { total, intent -> total + intent.amount },
            )

    /**
     * What a withdrawal can actually take out, pruning included.
     *
     * [remaining] is already the free balance: the escrow moves what an intent locks out of it and
     * into [outstandingIntentAmount], so netting the outstanding total off again would charge the
     * same lock twice and report nothing withdrawable on an order with funds sitting free. What
     * pruning adds back is only the part held by intents that have run out.
     */
    val withdrawableAfterPrune: Usdc6 get() = remaining + expiredIntentAmount

    /**
     * The one control an order surface offers, decided here so the two surfaces cannot drift.
     *
     * Withdrawing prunes on its own and is what stopping matching was almost always in service of,
     * so it takes precedence. The toggle is worth offering only in the case withdrawal cannot
     * reach: live intents holding the entire balance. Testing that on [remaining] instead never
     * fires, because [withdrawableAfterPrune] is [remaining] plus the expired holdings and any
     * positive [remaining] takes the withdrawal branch first.
     */
    val offersWithdrawal: Boolean get() = !phase.isFinished && withdrawableAfterPrune > Usdc6.ZERO

    val offersMatchingToggle: Boolean
        get() = !phase.isFinished && withdrawableAfterPrune <= Usdc6.ZERO && liveIntents.isNotEmpty()

    /** Exact identification of a submitted `createDeposit`, when the hash survived the crash. */
    fun wasCreatedBy(txHash: TxHash): Boolean = creationTxHash == txHash

    /**
     * The order a checkpoint could have opened. The block floor is what keeps a repeat cash-out of
     * the same size on the same rail from being mistaken for the one being reconciled, and the
     * currencies are what keep two orders of the same size opened minutes apart from being swapped.
     */
    fun couldHaveBeenOpenedBy(checkpoint: PeerCashOutCheckpoint, notBeforeBlock: Long?): Boolean =
        platform == checkpoint.platform &&
            payeeHash == checkpoint.payeeHash &&
            intentAmountMax == checkpoint.amount &&
            offeredCurrencies == checkpoint.currencies.toSet() &&
            (notBeforeBlock == null || (creationBlockNumber ?: 0L) >= notBeforeBlock)

    private val offeredCurrencies: Set<PeerCurrency> get() = currencies.mapNotNull { it.currency }.toSet()

    // A buyer who has taken the whole order leaves remaining at zero while they pay, so a live
    // intent outranks the balance. An expired intent counts as balance rather than as a buyer: the
    // escrow prunes it on the next signal, so the funds are still on offer and still the user's —
    // calling that order closed would file it away with no way left to reach the money.
    val phase: PeerOrderPhase
        get() =
            when {
                liveIntents.isNotEmpty() -> PeerOrderPhase.BUYER_PAYING
                withdrawableAfterPrune > Usdc6.ZERO && !acceptingIntents -> PeerOrderPhase.PAUSED
                withdrawableAfterPrune > Usdc6.ZERO && soldAmount > Usdc6.ZERO -> PeerOrderPhase.PARTLY_SOLD
                withdrawableAfterPrune > Usdc6.ZERO -> PeerOrderPhase.WAITING
                totalWithdrawn > Usdc6.ZERO -> PeerOrderPhase.CLOSED
                soldAmount > Usdc6.ZERO -> PeerOrderPhase.SOLD
                else -> PeerOrderPhase.CLOSED
            }

    val isTerminal: Boolean get() = status == PeerDepositStatus.CLOSED
}

private fun spanSeconds(fromSeconds: Long?, toSeconds: Long?): Long? {
    if (fromSeconds == null || toSeconds == null) return null
    return (toSeconds - fromSeconds).takeIf { it >= 0 }
}
