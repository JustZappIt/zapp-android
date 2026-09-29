// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd.reverse

import androidx.annotation.StringRes
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.atomicswap.label
import co.electriccoin.zcash.ui.design.component.zapp.ZappStep
import co.electriccoin.zcash.ui.design.component.zapp.ZappStepStatus
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.privateusd.PrivateUsdInfo
import co.electriccoin.zcash.ui.screen.privateusd.progress.PrivateUsdProgressState
import co.electriccoin.zcash.ui.screen.privateusd.progress.PrivateUsdResultState
import xyz.justzappit.offramp.atomicswap.ReversePhase
import xyz.justzappit.offramp.atomicswap.ReverseSwapRecord

internal fun reverseProgress(record: ReverseSwapRecord, state: PrivateUsdReverseState): PrivateUsdProgressState? {
    if (state.showAmount || state.showReview) return null
    return PrivateUsdProgressState(
        amounts =
            if (record.phase in setOf(ReversePhase.REFUNDED, ReversePhase.CANCELLED)) {
                null
            } else {
                stringRes(
                    if (state.receiveIsEstimate) {
                        R.string.reverse_progress_estimate
                    } else {
                        R.string.reverse_progress_amounts
                    },
                    state.debit ?: state.escrow ?: stringRes(""),
                    state.receive.orEmpty(),
                )
            },
        result =
            if (record.finished) {
                PrivateUsdResultState(
                    title =
                        stringRes(
                            when (record.phase) {
                                ReversePhase.COMPLETE -> R.string.reverse_result_received
                                ReversePhase.REFUNDED -> R.string.reverse_result_refunded
                                else -> R.string.reverse_result_cancelled
                            }
                        ),
                    body =
                        if (record.phase == ReversePhase.COMPLETE) {
                            stringRes(R.string.reverse_progress_received, state.receive.orEmpty())
                        } else {
                            state.status
                        },
                    isSuccess = record.phase == ReversePhase.COMPLETE,
                )
            } else {
                null
            },
        steps = reverseSteps(record.phase, record.receiveConfirmations),
        note = if (record.phase == ReversePhase.AWAITING_READY) stringRes(R.string.reverse_ready_explanation) else null,
        callOff = state.cancel ?: state.rescue,
        showsBackgroundNote = record.underWay,
        primaryButton = state.primary,
        info =
            PrivateUsdInfo(
                title = stringRes(R.string.reverse_title),
                steps = listOf(stringRes(R.string.reverse_intro)),
                notes = listOf(stringRes(R.string.reverse_gas_account), stringRes(R.string.reverse_refund_terms)),
            ),
        onBack = state.onBack,
    )
}

internal fun reverseSteps(phase: ReversePhase, receiveConfirmations: Long): List<ZappStep> {
    val current =
        phase.receiveStep()
            ?: return if (phase in REFUND_PHASES) {
                listOf(ZappStep(stringRes(phase.label()), ZappStepStatus.InProgress))
            } else {
                emptyList()
            }
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
