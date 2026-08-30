// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.apple

import xyz.justzappit.evm.math.decimalToPlainString
import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.offramp.p2p.Usdc6
import xyz.justzappit.offramp.peer.PeerCashOutCheckpoint
import xyz.justzappit.offramp.peer.PeerCashOutStatus
import xyz.justzappit.offramp.peer.PeerIntent
import xyz.justzappit.offramp.peer.PeerMarketSnapshot
import xyz.justzappit.offramp.peer.PeerMarketVerdict
import xyz.justzappit.offramp.peer.PeerNetworkConfig
import xyz.justzappit.offramp.peer.PeerOrderSnapshot
import xyz.justzappit.offramp.peer.PeerPlatform
import xyz.justzappit.offramp.peer.PeerRecovery
import xyz.justzappit.offramp.peer.PeerResumeAction
import xyz.justzappit.offramp.peer.depositId
import xyz.justzappit.offramp.peer.step

/**
 * Protocol types to the flat shapes Swift consumes. Kept apart from the client so the whole mapping
 * is reachable from a test without standing up an account, a network and three HTTP clients — the
 * stable codes here are an API contract, and a silent rename on one of them is a screen that stops
 * recognising the state it is in.
 *
 * Internal rather than private: module-visible for the tests, and Kotlin/Native exports only public
 * declarations, so none of this widens the Swift surface.
 */
internal fun PeerPlatform.toApple(): ApplePeerPlatformCapability =
    ApplePeerPlatformCapability(
        code = wireName,
        currencies = currencies.map { ApplePeerCurrency(code = it.code, symbol = it.symbol, precision = it.precision) },
        defaultCurrencyCodes = defaultCurrencies.map { it.code },
        validatesHandleLive = validatesHandleLive,
        offersCurrencyChoice = offersCurrencyChoice,
    )

internal fun PeerMarketSnapshot.toApple(amount: Usdc6?): ApplePeerMarket {
    val band = verdict as? PeerMarketVerdict.Band
    return ApplePeerMarket(
        platformCode = platform.wireName,
        currencyCode = currency.code,
        verdict =
            when (verdict) {
                is PeerMarketVerdict.Band -> ApplePeerMarket.VERDICT_BAND
                PeerMarketVerdict.LittleActivity -> ApplePeerMarket.VERDICT_LITTLE_ACTIVITY
                PeerMarketVerdict.Unknown -> ApplePeerMarket.VERDICT_UNKNOWN
            },
        fillsInWindow = fillsInWindow,
        averageFillMicros = averageFill?.micros?.toString(),
        lastFillSecondsAgo = lastFillSecondsAgo,
        waitLowSeconds = band?.lowSeconds ?: 0L,
        waitHighSeconds = band?.highSeconds ?: 0L,
        isOversized = amount != null && isOversized(amount),
    )
}

/**
 * The durable half of an attempt. The raw handle is absent by construction: a checkpoint carries
 * only the curator hash, and this mapping has nowhere to read a handle from even if it wanted one.
 */
internal fun PeerCashOutCheckpoint.toApple(): ApplePeerAttempt =
    ApplePeerAttempt(
        id = id.value,
        platformCode = platform.wireName,
        currencyCodes = currencies.map { it.code },
        amountMicros = amount.micros.toString(),
        createdAtEpochSeconds = createdAtMillis / MILLIS_PER_SECOND,
        depositIdComposite = depositId?.composite,
        holdsUnescrowedFunds = holdsUnescrowedFunds,
        resumeAction =
            when (resumeAction) {
                is PeerResumeAction.ReadOrder -> ApplePeerAttempt.RESUME_READ_ORDER
                is PeerResumeAction.ResolveSubmittedDeposit -> ApplePeerAttempt.RESUME_RESOLVE_SUBMITTED
                PeerResumeAction.ReconcileSubmission -> ApplePeerAttempt.RESUME_RECONCILE
                is PeerResumeAction.ResumeBridge -> ApplePeerAttempt.RESUME_BRIDGE
                PeerResumeAction.FreshStart -> ApplePeerAttempt.RESUME_FRESH_START
            },
    )

internal fun PeerCashOutStatus.toApple(subjectId: String, peerNetwork: PeerNetworkConfig?): ApplePeerStatus {
    val snapshot = (this as? PeerCashOutStatus.OrderLive)?.snapshot
    return ApplePeerStatus(
        subjectId = subjectId,
        kind = appleKind(),
        step = step.name,
        amountMicros = appleAmount()?.micros?.toString(),
        txHash = appleTxHash()?.hex,
        payeeHashHex = (this as? PeerCashOutStatus.ValidatingPayee)?.payeeHash?.hex,
        depositIdComposite = depositId?.composite,
        order = snapshot?.toApple(peerNetwork),
        failure = (this as? PeerCashOutStatus.Failed)?.toApple(),
        // Terminal describes the status, never the operation: a live order that has sold out is
        // finished, while one still on offer is re-emitted by the poll for as long as it is watched.
        isTerminal =
            this is PeerCashOutStatus.Failed ||
                this is PeerCashOutStatus.Withdrawn ||
                snapshot?.phase?.isFinished == true,
    )
}

internal fun PeerCashOutStatus.Failed.toApple(): ApplePeerFailure =
    ApplePeerFailure(
        code = error.code.name,
        step = step.name,
        retryable = error.retryable,
        allowsManualRetry = error.allowsManualRetry,
        nothingEscrowed = error.nothingEscrowed,
        recoveryKind =
            when (error.recovery) {
                is PeerRecovery.InspectBaseTransaction -> ApplePeerFailure.RECOVERY_INSPECT_TRANSACTION
                is PeerRecovery.InspectDepositor -> ApplePeerFailure.RECOVERY_INSPECT_DEPOSITOR
                null -> null
            },
        recoveryTxHash = (error.recovery as? PeerRecovery.InspectBaseTransaction)?.txHash?.hex,
        recoveryAddress = (error.recovery as? PeerRecovery.InspectDepositor)?.depositor?.checksumHex,
        // The decoded revert is more specific than the code that carried it, and only the bucket is
        // something a user can act on; the rest belongs in a bug report.
        escrowRevertBucket = error.escrowRevert?.userFacing?.name,
    )

internal fun PeerOrderSnapshot.toApple(peerNetwork: PeerNetworkConfig?): ApplePeerOrder =
    ApplePeerOrder(
        depositIdComposite = id.composite,
        phase = phase.name,
        isFinished = phase.isFinished,
        acceptingIntents = acceptingIntents,
        grossMicros = grossAmount.micros.toString(),
        remainingMicros = remaining.micros.toString(),
        soldMicros = soldAmount.micros.toString(),
        lockedMicros = outstandingIntentAmount.micros.toString(),
        withdrawnMicros = totalWithdrawn.micros.toString(),
        withdrawableMicros = withdrawableAfterPrune.micros.toString(),
        platformCode = platform?.wireName,
        currencies =
            currencies.map {
                ApplePeerOrderCurrency(
                    code = it.currency?.code,
                    spreadBasisPoints = it.spread.value,
                    oracleRate = it.oracleRate?.let { rate -> decimalToPlainString(rate.decimal) },
                    lastOracleUpdateEpochSeconds = it.lastOracleUpdatedAtSeconds,
                )
            },
        intents = intentsNewestFirst.map { it.toApple() },
        offersWithdrawal = offersWithdrawal,
        offersMatchingToggle = offersMatchingToggle,
        isHiddenFromBuyers = isHiddenFromBuyers,
        isAllOrNothing = isAllOrNothing,
        creationTxHash = creationTxHash?.hex,
        openedAtEpochSeconds = openedAtSeconds,
        lastActivityAtEpochSeconds = lastActivityAtSeconds,
        secondsToFirstBuyer = secondsToFirstBuyer,
        explorerUrl = peerNetwork?.orderUrl(id),
    )

internal fun PeerIntent.toApple(): ApplePeerIntent =
    ApplePeerIntent(
        intentHash = intentHash,
        outcome = outcome.name,
        amountMicros = amount.micros.toString(),
        releasedMicros = releasedAmount.micros.toString(),
        paymentCurrencyCode = paymentCurrency?.code,
        // Quantised to the currency the buyer pays in: no rail can charge the six decimals the
        // protocol counts in, so the raw minor units would show an amount nobody can send.
        paymentAmount = paymentCurrency?.let(paymentAmount::toDisplayString),
        signalledAtEpochSeconds = signalTimestampSeconds,
        expiresAtEpochSeconds = expiryTimeSeconds,
        settlementTxHash = settlementTxHash?.hex,
        holdsFunds = outcome.holdsFunds,
        isPaidOut = outcome.isPaidOut,
    )

private fun PeerCashOutStatus.appleKind(): String =
    when (this) {
        PeerCashOutStatus.Idle -> ApplePeerStatus.KIND_IDLE

        is PeerCashOutStatus.ValidatingPayee -> ApplePeerStatus.KIND_VALIDATING_PAYEE

        // iOS never starts a bridge from a cash-out, so a bridge emission could only come from
        // resuming one an older record left in flight. It is funding either way.
        is PeerCashOutStatus.BridgingFunds -> ApplePeerStatus.KIND_FUNDED

        is PeerCashOutStatus.FundedFromBase -> ApplePeerStatus.KIND_FUNDED

        is PeerCashOutStatus.ApprovingUsdc -> ApplePeerStatus.KIND_APPROVING_USDC

        is PeerCashOutStatus.CreatingDeposit -> ApplePeerStatus.KIND_CREATING_DEPOSIT

        is PeerCashOutStatus.OrderLive -> ApplePeerStatus.KIND_ORDER_LIVE

        is PeerCashOutStatus.Withdrawing -> ApplePeerStatus.KIND_WITHDRAWING

        is PeerCashOutStatus.Withdrawn -> ApplePeerStatus.KIND_WITHDRAWN

        is PeerCashOutStatus.Failed -> ApplePeerStatus.KIND_FAILED
    }

private fun PeerCashOutStatus.appleAmount(): Usdc6? =
    when (this) {
        is PeerCashOutStatus.BridgingFunds -> amount

        is PeerCashOutStatus.FundedFromBase -> amount

        is PeerCashOutStatus.ApprovingUsdc -> amount

        is PeerCashOutStatus.CreatingDeposit -> amount

        is PeerCashOutStatus.Withdrawing -> amount

        is PeerCashOutStatus.Withdrawn -> amount

        // The order at the size it was funded, not what is left, so the progress screen's figure
        // does not shrink the moment a buyer locks part of it.
        is PeerCashOutStatus.OrderLive -> snapshot.grossAmount

        PeerCashOutStatus.Idle,
        is PeerCashOutStatus.ValidatingPayee,
        is PeerCashOutStatus.Failed,
        -> null
    }

private fun PeerCashOutStatus.appleTxHash(): TxHash? =
    when (this) {
        is PeerCashOutStatus.ApprovingUsdc -> txHash

        is PeerCashOutStatus.CreatingDeposit -> txHash

        is PeerCashOutStatus.Withdrawing -> txHash

        is PeerCashOutStatus.Withdrawn -> txHash

        is PeerCashOutStatus.Failed -> txHash

        PeerCashOutStatus.Idle,
        is PeerCashOutStatus.ValidatingPayee,
        is PeerCashOutStatus.BridgingFunds,
        is PeerCashOutStatus.FundedFromBase,
        is PeerCashOutStatus.OrderLive,
        -> null
    }

private const val MILLIS_PER_SECOND = 1_000L
