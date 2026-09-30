// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd.reverse

import androidx.annotation.StringRes
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.atomicswap.ZcashWait
import co.electriccoin.zcash.ui.common.atomicswap.label
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.zapp.ZappStep
import co.electriccoin.zcash.ui.design.component.zapp.ZappStepStatus
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.privateusd.PrivateUsdInfo
import co.electriccoin.zcash.ui.screen.privateusd.progress.PrivateUsdProblemState
import co.electriccoin.zcash.ui.screen.privateusd.progress.PrivateUsdProgressState
import co.electriccoin.zcash.ui.screen.privateusd.progress.PrivateUsdResultState
import co.electriccoin.zcash.ui.screen.privateusd.progress.stepDetail
import xyz.justzappit.offramp.atomicswap.ReversePhase
import xyz.justzappit.offramp.atomicswap.ReverseSwapRecord
import xyz.justzappit.offramp.atomicswap.ReverseSwapResult
import xyz.justzappit.offramp.atomicswap.ReverseSwapStatus

/** What a reverse conversion's progress screen shows, from the moment it's paid for. */
internal fun reverseProgress(
    record: ReverseSwapRecord,
    paid: StringResource,
    received: StringResource,
    callOff: ButtonState?,
    problem: PrivateUsdProblemState?,
    zcashWait: ZcashWait?,
    error: StringResource?,
    primary: ButtonState?,
    info: PrivateUsdInfo,
    isBackEnabled: Boolean,
    onBack: () -> Unit,
): PrivateUsdProgressState {
    val ended = (record.status as? ReverseSwapStatus.Over)?.result
    return PrivateUsdProgressState(
        amounts =
            stringRes(
                if (record.receive == null) R.string.reverse_progress_estimate else R.string.reverse_progress_amounts,
                paid,
                received,
            ).takeUnless { ended == ReverseSwapResult.REFUNDED || ended == ReverseSwapResult.CANCELLED },
        result = ended?.let { resultOf(record.phase, it, received) },
        steps = reverseSteps(record.phase, record.receiveConfirmations, zcashWait),
        note = stringRes(R.string.reverse_ready_explanation).takeIf { record.phase == ReversePhase.AWAITING_READY },
        problem = problem,
        error = error,
        callOff = callOff,
        showsBackgroundNote = record.underWay,
        primaryButton = primary,
        info = info,
        onBack = onBack,
        isBackEnabled = isBackEnabled,
    )
}

private fun resultOf(
    phase: ReversePhase,
    result: ReverseSwapResult,
    received: StringResource
) = PrivateUsdResultState(
    title =
        stringRes(
            when (result) {
                ReverseSwapResult.RECEIVED -> R.string.reverse_result_received
                ReverseSwapResult.REFUNDED -> R.string.reverse_result_refunded
                ReverseSwapResult.CANCELLED -> R.string.reverse_result_cancelled
            }
        ),
    body =
        if (result == ReverseSwapResult.RECEIVED) {
            stringRes(R.string.reverse_progress_received, received)
        } else {
            stringRes(phase.label())
        },
    isSuccess = result == ReverseSwapResult.RECEIVED,
)

internal fun reverseSteps(
    phase: ReversePhase,
    receiveConfirmations: Long,
    zcashWait: ZcashWait? = null,
): List<ZappStep> {
    val current =
        phase.receiveStep()
            ?: return if (phase in REFUND_PHASES) {
                listOf(ZappStep(stringRes(phase.label()), ZappStepStatus.InProgress))
            } else {
                emptyList()
            }
    val waiting = zcashWait?.stepDetail()
    return ReceiveStep.entries.map { step ->
        ZappStep(
            label = stringRes(step.label),
            status =
                when {
                    phase == ReversePhase.COMPLETE || step < current -> ZappStepStatus.Completed
                    step == current -> ZappStepStatus.InProgress
                    else -> ZappStepStatus.Pending
                },
            detailLines =
                when {
                    step == current && waiting != null -> {
                        listOf(waiting)
                    }

                    step == ReceiveStep.RECEIVE && receiveConfirmations > 0 -> {
                        listOf(stringRes(R.string.reverse_sweep_confirmations, receiveConfirmations))
                    }

                    step == ReceiveStep.APPROVE && phase == ReversePhase.AWAITING_READY -> {
                        listOf(stringRes(R.string.reverse_step_approval_detail))
                    }

                    else -> {
                        emptyList()
                    }
                },
        )
    }
}

/** A short name for where the conversion is: its step, or its refund's. */
@StringRes
internal fun ReversePhase.stageLabel(): Int = receiveStep()?.label ?: label()

private fun ReversePhase.receiveStep(): ReceiveStep? =
    when (this) {
        ReversePhase.ACCEPTING -> ReceiveStep.PREPARE

        ReversePhase.AWAITING_FUNDING, ReversePhase.SENDING_USDC, ReversePhase.CONFIRMING_ESCROW -> ReceiveStep.FUND

        ReversePhase.RECEIVING_ZEC -> ReceiveStep.DEPOSIT

        ReversePhase.AWAITING_READY -> ReceiveStep.APPROVE

        ReversePhase.SETTLING -> ReceiveStep.SETTLE

        ReversePhase.RECEIVING, ReversePhase.COMPLETE -> ReceiveStep.RECEIVE

        ReversePhase.QUOTED, ReversePhase.REFUND_WAIT, ReversePhase.REFUNDING, ReversePhase.REFUND_PAYOUT,
        ReversePhase.REFUNDED, ReversePhase.CANCELLED -> null
    }

private val REFUND_PHASES = setOf(ReversePhase.REFUND_WAIT, ReversePhase.REFUNDING, ReversePhase.REFUND_PAYOUT)

private enum class ReceiveStep(
    @get:StringRes val label: Int
) {
    PREPARE(R.string.reverse_accepting),
    FUND(R.string.reverse_step_fund),
    DEPOSIT(R.string.reverse_receiving),
    APPROVE(R.string.reverse_ready),
    SETTLE(R.string.reverse_step_settle),
    RECEIVE(R.string.reverse_sweeping),
}
