package co.electriccoin.zcash.ui.screen.swap.peer.progress

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.zapp.ZappStep
import co.electriccoin.zcash.ui.design.component.zapp.ZappStepStatus
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.swap.peer.displayName
import xyz.justzappit.offramp.p2p.Usdc6
import xyz.justzappit.offramp.peer.PeerCashOutStatus
import xyz.justzappit.offramp.peer.PeerCashOutStep
import xyz.justzappit.offramp.peer.PeerOrderPhase
import xyz.justzappit.offramp.peer.PeerPlatform
import xyz.justzappit.offramp.peer.step

/**
 * Pure mapper: orchestrator status to the rows the shared [ZappStepList] renders. Same shape as the
 * p2p.me offramp's mapper so both products read as one flow, and testable without a ViewModel.
 *
 * Two rows are conditional. FUNDING appears only when a bridge actually ran, and WITHDRAWING only
 * once an unwind is under way, because an order that fills completely never withdraws and a
 * permanently pending row would read as something left undone.
 */
internal fun buildPeerProgressSteps(
    status: PeerCashOutStatus,
    platform: PeerPlatform,
    bridgingObserved: Boolean = false,
): List<ZappStep> {
    val order =
        PeerCashOutStep.UI_PROGRESS.filter { step ->
            when (step) {
                PeerCashOutStep.FUNDING -> shouldShowFundingStep(status, bridgingObserved)
                PeerCashOutStep.WITHDRAWING -> shouldShowWithdrawStep(status)
                else -> true
            }
        }
    val currentStep = status.step.takeIf { status !is PeerCashOutStatus.Failed }
    val failedStep = (status as? PeerCashOutStatus.Failed)?.step
    val bridging = status is PeerCashOutStatus.BridgingFunds || bridgingObserved
    return order.mapIndexed { index, step ->
        ZappStep(
            label = labelFor(step, status, platform, bridging),
            status = computeStepStatus(index, order, currentStep, failedStep, status),
            detailLines = stepDetail(status, step),
        )
    }
}

private fun shouldShowFundingStep(status: PeerCashOutStatus, bridgingObserved: Boolean): Boolean =
    when {
        status is PeerCashOutStatus.BridgingFunds || bridgingObserved -> true
        status is PeerCashOutStatus.Failed && status.step == PeerCashOutStep.FUNDING -> true
        else -> false
    }

private fun shouldShowWithdrawStep(status: PeerCashOutStatus): Boolean =
    when {
        status is PeerCashOutStatus.Withdrawing || status is PeerCashOutStatus.Withdrawn -> true
        status is PeerCashOutStatus.Failed && status.step == PeerCashOutStep.WITHDRAWING -> true
        else -> false
    }

private fun labelFor(
    step: PeerCashOutStep,
    status: PeerCashOutStatus,
    platform: PeerPlatform,
    bridging: Boolean,
): StringResource =
    when {
        // The waiting row reads "Waiting for a buyer" while polling; once the order is fully taken
        // it would misleadingly still say waiting, so it flips to a done label.
        step == PeerCashOutStep.AWAITING_BUYER && status.isSold -> {
            stringRes(R.string.peer_offramp_step_sold)
        }

        // A cash-out spends Base USDC the user already has, so this row is a balance check unless it
        // is finishing a bridge an older attempt left in flight.
        step == PeerCashOutStep.FUNDING && bridging -> {
            stringRes(R.string.peer_offramp_step_bridging)
        }

        else -> {
            stepLabel(step, platform)
        }
    }

internal fun stepLabel(step: PeerCashOutStep, platform: PeerPlatform): StringResource =
    when (step) {
        PeerCashOutStep.INITIALIZATION -> stringRes(R.string.peer_offramp_step_init)
        PeerCashOutStep.VALIDATING_PAYEE -> stringRes(R.string.peer_offramp_step_validating, platform.displayName())
        PeerCashOutStep.FUNDING -> stringRes(R.string.peer_offramp_step_checking_funds)
        PeerCashOutStep.APPROVING_USDC -> stringRes(R.string.peer_offramp_step_approve)
        PeerCashOutStep.CREATING_DEPOSIT -> stringRes(R.string.peer_offramp_step_create_order)
        PeerCashOutStep.AWAITING_BUYER -> stringRes(R.string.peer_offramp_step_awaiting_buyer)
        PeerCashOutStep.SETTLING -> stringRes(R.string.peer_offramp_step_settling)
        PeerCashOutStep.WITHDRAWING -> stringRes(R.string.peer_offramp_step_withdrawing)
    }

private fun computeStepStatus(
    index: Int,
    order: List<PeerCashOutStep>,
    currentStep: PeerCashOutStep?,
    failedStep: PeerCashOutStep?,
    status: PeerCashOutStatus,
): ZappStepStatus =
    when {
        failedStep != null -> {
            relativeTo(index, order.indexOf(failedStep).coerceAtLeast(0), failedIsCurrent = true)
        }

        status is PeerCashOutStatus.Withdrawn || status.isSold -> {
            ZappStepStatus.Completed
        }

        currentStep == null -> {
            ZappStepStatus.Pending
        }

        // A step that has no row of its own (a conditional one that is hidden) resolves to the next
        // visible row, so everything before it still reads as done.
        order.indexOf(currentStep) < 0 -> {
            if (index < nextVisibleIndexAfter(currentStep, order)) {
                ZappStepStatus.Completed
            } else {
                ZappStepStatus.Pending
            }
        }

        else -> {
            relativeTo(index, order.indexOf(currentStep), failedIsCurrent = false)
        }
    }

private fun relativeTo(index: Int, pivot: Int, failedIsCurrent: Boolean): ZappStepStatus =
    when {
        index < pivot -> ZappStepStatus.Completed
        index > pivot -> ZappStepStatus.Pending
        failedIsCurrent -> ZappStepStatus.Failed
        else -> ZappStepStatus.InProgress
    }

private fun nextVisibleIndexAfter(step: PeerCashOutStep, order: List<PeerCashOutStep>): Int {
    val canonicalIndex = PeerCashOutStep.UI_PROGRESS.indexOf(step)
    val nextStep =
        canonicalIndex
            .takeIf { it >= 0 }
            ?.let { PeerCashOutStep.UI_PROGRESS.drop(it + 1).firstOrNull { next -> next in order } }
    return when {
        canonicalIndex < 0 -> 0
        nextStep == null -> order.size
        else -> order.indexOf(nextStep)
    }
}

private fun stepDetail(status: PeerCashOutStatus, step: PeerCashOutStep): List<StringResource> =
    when (step) {
        PeerCashOutStep.FUNDING -> {
            emptyList()
        }

        PeerCashOutStep.AWAITING_BUYER -> {
            (status as? PeerCashOutStatus.OrderLive)
                ?.takeIf { it.snapshot.soldAmount > Usdc6.ZERO }
                ?.let {
                    listOf(
                        stringRes(
                            R.string.peer_offramp_detail_partial_fill,
                            it.snapshot.soldAmount.toDisplayString(stripTrailingZeros = true),
                            it.snapshot.grossAmount.toDisplayString(stripTrailingZeros = true),
                        ),
                    )
                }.orEmpty()
        }

        PeerCashOutStep.SETTLING -> {
            (status as? PeerCashOutStatus.OrderLive)
                ?.snapshot
                ?.liveIntents
                ?.firstOrNull()
                ?.let { intent ->
                    val currency = intent.paymentCurrency ?: return@let null
                    listOf(
                        stringRes(
                            R.string.peer_offramp_detail_buyer_owes,
                            intent.paymentAmount.toDisplayString(currency),
                            currency.code,
                        ),
                    )
                }.orEmpty()
        }

        else -> {
            emptyList()
        }
    }

private val PeerCashOutStatus.isSold: Boolean
    get() = this is PeerCashOutStatus.OrderLive && snapshot.phase == PeerOrderPhase.SOLD
