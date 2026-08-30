// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.apple

/**
 * The Swift-facing shape of the Peer maker flow. Every type here is a flat data class of strings,
 * integers and booleans, because the protocol's own vocabulary — Kotlin inline value classes,
 * `BigInteger`, sealed hierarchies, flows that throw — does not survive the Objective-C bridge in a
 * form a reducer can pattern-match on.
 *
 * Three conventions hold throughout, and each one exists because its alternative has a failure mode:
 *  - money is an integer micro-unit string, never a `Double`, so no amount is ever rounded in
 *    transit between the escrow and the screen;
 *  - rates are plain decimal strings for the same reason;
 *  - every category is a stable uppercase code, listed in the companion beside the type it belongs
 *    to, so Swift maps codes to localized text and no display string is ever exported from Kotlin.
 */
data class ApplePeerCapabilities(
    val isAvailable: Boolean,
    val networkName: String?,
    val platforms: List<ApplePeerPlatformCapability>,
    val minimumMicros: String,
    val recommendedMinimumMicros: String,
    /** How many random bytes an attempt id is. Swift mints them; Kotlin validates and stores them. */
    val attemptIdByteCount: Int,
)

/** One ungated rail, with the currency set and the handle rules that differ between them. */
data class ApplePeerPlatformCapability(
    val code: String,
    val currencies: List<ApplePeerCurrency>,
    val defaultCurrencyCodes: List<String>,
    val validatesHandleLive: Boolean,
    val offersCurrencyChoice: Boolean,
)

data class ApplePeerCurrency(
    val code: String,
    val symbol: String,
    val precision: Int,
)

/**
 * What the handle the user typed becomes, before any of it reaches the curator. [normalized] is
 * null when the rail's format rules rule the handle out, which is the only client-side rejection:
 * the curator stays authoritative about whether an account exists.
 */
data class ApplePeerHandleCheck(
    val normalized: String?,
    val changedWhatWasTyped: Boolean,
    val validatesLive: Boolean,
)

/** The Base account a cash-out spends from. A null balance is a failed read, never a zero balance. */
data class ApplePeerAccount(
    val address: String,
    val balanceMicros: String?,
    val explorerUrl: String,
)

/** Indicative only. The binding rate is whatever the oracle says when a buyer signals. */
data class ApplePeerRate(
    val currencyCode: String,
    val fiatPerUsdc: String,
    val readAtEpochSeconds: Long,
)

data class ApplePeerMarket(
    val platformCode: String,
    val currencyCode: String,
    val verdict: String,
    val fillsInWindow: Int,
    val averageFillMicros: String?,
    val lastFillSecondsAgo: Long?,
    /** Both zero unless [verdict] is [VERDICT_BAND]; a band is a range, never a point estimate. */
    val waitLowSeconds: Long,
    val waitHighSeconds: Long,
    /** Set when the amount asked about is large enough to fill in pieces over hours. */
    val isOversized: Boolean,
) {
    companion object {
        const val VERDICT_BAND = "BAND"
        const val VERDICT_LITTLE_ACTIVITY = "LITTLE_ACTIVITY"
        const val VERDICT_UNKNOWN = "UNKNOWN"
    }
}

/** Everything the user chose, validated on the Kotlin side before a single call is made. */
data class ApplePeerCashOutRequest(
    val attemptId: String,
    val platformCode: String,
    val handle: String,
    val currencyCodes: List<String>,
    val amountMicros: String,
)

/**
 * An attempt the app remembers but is not necessarily driving: the durable half of a cash-out, read
 * back from the checkpoint book. The raw handle is deliberately absent — a checkpoint carries only
 * the curator hash, which is the only part of a payee the protocol needs.
 */
data class ApplePeerAttempt(
    val id: String,
    val platformCode: String,
    val currencyCodes: List<String>,
    val amountMicros: String,
    val createdAtEpochSeconds: Long,
    val depositIdComposite: String?,
    /** Whether [amountMicros] is still sitting in the smart account rather than in the escrow. */
    val holdsUnescrowedFunds: Boolean,
    val resumeAction: String,
) {
    companion object {
        const val RESUME_READ_ORDER = "READ_ORDER"
        const val RESUME_RESOLVE_SUBMITTED = "RESOLVE_SUBMITTED"
        const val RESUME_RECONCILE = "RECONCILE"
        const val RESUME_BRIDGE = "RESUME_BRIDGE"
        const val RESUME_FRESH_START = "FRESH_START"
    }
}

/** An attempt matched to the deposit it turned out to open, so its reservation can be released. */
data class ApplePeerReconciliation(
    val attemptId: String,
    val depositIdComposite: String,
)

/**
 * One emission from a running cash-out, withdrawal or matching toggle. A single flat shape rather
 * than a bridged sealed hierarchy: [kind] is the discriminator, and the fields a kind does not use
 * are null.
 */
data class ApplePeerStatus(
    /** The cash-out attempt, or the deposit id when the operation acts on an order that exists. */
    val subjectId: String,
    val kind: String,
    val step: String,
    val amountMicros: String? = null,
    val txHash: String? = null,
    val payeeHashHex: String? = null,
    val depositIdComposite: String? = null,
    val order: ApplePeerOrder? = null,
    val failure: ApplePeerFailure? = null,
    val isTerminal: Boolean = false,
) {
    companion object {
        const val KIND_IDLE = "IDLE"
        const val KIND_VALIDATING_PAYEE = "VALIDATING_PAYEE"
        const val KIND_FUNDED = "FUNDED"
        const val KIND_APPROVING_USDC = "APPROVING_USDC"
        const val KIND_CREATING_DEPOSIT = "CREATING_DEPOSIT"
        const val KIND_ORDER_LIVE = "ORDER_LIVE"
        const val KIND_WITHDRAWING = "WITHDRAWING"
        const val KIND_WITHDRAWN = "WITHDRAWN"
        const val KIND_FAILED = "FAILED"
    }
}

/**
 * A failure with the three contracts the money depends on carried explicitly, because Swift must
 * never re-derive them from [code]:
 *
 *  - [retryable] — safe to retry automatically;
 *  - [allowsManualRetry] — safe to even offer the user a retry button. False on the three
 *    unknown-outcome codes, where a second attempt is how one deposit becomes two;
 *  - [nothingEscrowed] — the failure proves the USDC never left the account. Only a proven negative
 *    releases the amount an attempt had reserved.
 */
data class ApplePeerFailure(
    val code: String,
    val step: String,
    val retryable: Boolean,
    val allowsManualRetry: Boolean,
    val nothingEscrowed: Boolean,
    val recoveryKind: String? = null,
    val recoveryTxHash: String? = null,
    val recoveryAddress: String? = null,
    val escrowRevertBucket: String? = null,
) {
    companion object {
        const val RECOVERY_INSPECT_TRANSACTION = "INSPECT_TRANSACTION"
        const val RECOVERY_INSPECT_DEPOSITOR = "INSPECT_DEPOSITOR"
    }
}

/**
 * An order as the chain and the indexer describe it. Every number the user is shown comes from here
 * rather than from anything the app remembers, which is what lets the order survive process death,
 * reinstall and a different device on the same seed.
 */
@Suppress("LongParameterList")
data class ApplePeerOrder(
    val depositIdComposite: String,
    val phase: String,
    val isFinished: Boolean,
    val acceptingIntents: Boolean,
    /** The order at the size it was funded, which no single escrow counter reports. */
    val grossMicros: String,
    val remainingMicros: String,
    val soldMicros: String,
    val lockedMicros: String,
    val withdrawnMicros: String,
    /** What a withdrawal can take out, expired intents pruned. */
    val withdrawableMicros: String,
    val platformCode: String?,
    val currencies: List<ApplePeerOrderCurrency>,
    val intents: List<ApplePeerIntent>,
    val offersWithdrawal: Boolean,
    val offersMatchingToggle: Boolean,
    /** Still valid, but below the floor Peer's orderbook lists, so no buyer is browsing it. */
    val isHiddenFromBuyers: Boolean,
    val isAllOrNothing: Boolean,
    val creationTxHash: String?,
    val openedAtEpochSeconds: Long?,
    val lastActivityAtEpochSeconds: Long?,
    val secondsToFirstBuyer: Long?,
    val explorerUrl: String?,
) {
    companion object {
        const val PHASE_WAITING = "WAITING"
        const val PHASE_BUYER_PAYING = "BUYER_PAYING"
        const val PHASE_PARTLY_SOLD = "PARTLY_SOLD"
        const val PHASE_SOLD = "SOLD"
        const val PHASE_PAUSED = "PAUSED"
        const val PHASE_CLOSED = "CLOSED"
    }
}

data class ApplePeerOrderCurrency(
    val code: String?,
    val spreadBasisPoints: Int,
    val oracleRate: String?,
    val lastOracleUpdateEpochSeconds: Long?,
)

/** One buyer's leg of an order. [holdsFunds] is what keeps a withdrawal from being offered. */
data class ApplePeerIntent(
    val intentHash: String,
    val outcome: String,
    val amountMicros: String,
    val releasedMicros: String,
    val paymentCurrencyCode: String?,
    val paymentAmount: String?,
    val signalledAtEpochSeconds: Long?,
    val expiresAtEpochSeconds: Long?,
    val settlementTxHash: String?,
    val holdsFunds: Boolean,
    val isPaidOut: Boolean,
) {
    companion object {
        const val OUTCOME_PAYING = "PAYING"
        const val OUTCOME_OUT_OF_TIME = "OUT_OF_TIME"
        const val OUTCOME_PAID = "PAID"
        const val OUTCOME_BACKED_OUT = "BACKED_OUT"
        const val OUTCOME_TIMED_OUT = "TIMED_OUT"
        const val OUTCOME_UNKNOWN = "UNKNOWN"
    }
}
