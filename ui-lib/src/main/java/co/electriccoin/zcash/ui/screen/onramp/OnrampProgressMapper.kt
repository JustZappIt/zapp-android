// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.onramp

import xyz.justzappit.offramp.onramp.OnrampFailureCode
import xyz.justzappit.offramp.onramp.OnrampPhase
import xyz.justzappit.offramp.onramp.OnrampStatus
import xyz.justzappit.offramp.onramp.phase

internal enum class OnrampVisibleStep {
    ORDER_PLACED,
    MERCHANT_MATCHED,
    PAY_MERCHANT,
    PAYMENT_CONFIRMED,
    RECEIVING_USDC,
    USDC_RECEIVED,
}

internal enum class OnrampStepState { PENDING, IN_PROGRESS, COMPLETED, FAILED }

internal data class OnrampProgressStep(
    val step: OnrampVisibleStep,
    val state: OnrampStepState,
)

internal fun mapOnrampProgress(status: OnrampStatus?): List<OnrampProgressStep> {
    val current = status ?: OnrampStatus.Idle
    val activeIndex = current.activeIndex()
    val terminalFailure = current is OnrampStatus.Cancelled || current is OnrampStatus.Failed
    return OnrampVisibleStep.entries.mapIndexed { index, step ->
        val state =
            when {
                index < activeIndex -> OnrampStepState.COMPLETED
                index == activeIndex && terminalFailure -> OnrampStepState.FAILED
                index == activeIndex -> OnrampStepState.IN_PROGRESS
                else -> OnrampStepState.PENDING
            }
        OnrampProgressStep(step, state)
    }
}

private fun OnrampStatus.activeIndex(): Int =
    when (phase) {
        OnrampPhase.PLACING -> OnrampVisibleStep.ORDER_PLACED.ordinal
        OnrampPhase.AWAITING_MERCHANT -> OnrampVisibleStep.MERCHANT_MATCHED.ordinal
        OnrampPhase.AWAITING_PAYMENT -> OnrampVisibleStep.PAY_MERCHANT.ordinal
        OnrampPhase.CONFIRMING_PAID -> OnrampVisibleStep.PAYMENT_CONFIRMED.ordinal
        OnrampPhase.AWAITING_SETTLEMENT -> OnrampVisibleStep.RECEIVING_USDC.ordinal
        OnrampPhase.COMPLETED -> OnrampVisibleStep.entries.size
        OnrampPhase.EXPIRED -> expiredIndex()
        OnrampPhase.CANCELLED -> OnrampVisibleStep.MERCHANT_MATCHED.ordinal
        OnrampPhase.FAILED -> OnrampVisibleStep.ORDER_PLACED.ordinal
    }

/**
 * A terminal phase says the order stopped, not where. The failure code is the only thing that
 * distinguishes an order nobody took from one whose payment window lapsed, and reddening the
 * merchant row for the latter tells the user the opposite of what happened.
 */
private fun OnrampStatus.expiredIndex(): Int =
    if ((this as? OnrampStatus.Failed)?.code == OnrampFailureCode.ORDER_EXPIRED) {
        OnrampVisibleStep.PAY_MERCHANT.ordinal
    } else {
        OnrampVisibleStep.MERCHANT_MATCHED.ordinal
    }
