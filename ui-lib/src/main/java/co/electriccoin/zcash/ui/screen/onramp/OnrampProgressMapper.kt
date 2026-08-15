// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.onramp

import xyz.justzappit.offramp.onramp.OnrampDestination
import xyz.justzappit.offramp.onramp.OnrampFailureCode
import xyz.justzappit.offramp.onramp.OnrampPhase
import xyz.justzappit.offramp.onramp.OnrampStatus
import xyz.justzappit.offramp.onramp.OnrampZecDeliveryStatus
import xyz.justzappit.offramp.onramp.phase

internal enum class OnrampVisibleStep {
    ORDER_PLACED,
    MERCHANT_MATCHED,
    PAY_MERCHANT,
    PAYMENT_CONFIRMED,
    RECEIVING_USDC,
    USDC_RECEIVED,
    CONVERTING_TO_ZEC,
    ZEC_RECEIVED,
}

internal enum class OnrampStepState { PENDING, IN_PROGRESS, COMPLETED, FAILED }

internal data class OnrampProgressStep(
    val step: OnrampVisibleStep,
    val state: OnrampStepState,
)

internal fun mapOnrampProgress(
    status: OnrampStatus?,
    delivery: OnrampZecDeliveryStatus? = null,
    destination: OnrampDestination = OnrampDestination.BASE,
): List<OnrampProgressStep> {
    val current = status ?: OnrampStatus.Idle
    val steps =
        if (destination == OnrampDestination.ZCASH) {
            OnrampVisibleStep.entries
        } else {
            OnrampVisibleStep.entries.take(BASE_STEP_COUNT)
        }
    val activeIndex = delivery?.activeIndex() ?: current.activeIndex(destination)
    val terminalFailure =
        current is OnrampStatus.Cancelled ||
            current is OnrampStatus.Failed ||
            delivery is OnrampZecDeliveryStatus.Failed ||
            delivery is OnrampZecDeliveryStatus.RefundedToBase
    return steps.mapIndexed { index, step ->
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

private fun OnrampStatus.activeIndex(destination: OnrampDestination): Int =
    when (phase) {
        OnrampPhase.PLACING -> {
            OnrampVisibleStep.ORDER_PLACED.ordinal
        }

        OnrampPhase.AWAITING_MERCHANT -> {
            OnrampVisibleStep.MERCHANT_MATCHED.ordinal
        }

        OnrampPhase.AWAITING_PAYMENT -> {
            OnrampVisibleStep.PAY_MERCHANT.ordinal
        }

        OnrampPhase.CONFIRMING_PAID -> {
            OnrampVisibleStep.PAYMENT_CONFIRMED.ordinal
        }

        OnrampPhase.AWAITING_SETTLEMENT -> {
            OnrampVisibleStep.RECEIVING_USDC.ordinal
        }

        OnrampPhase.COMPLETED -> {
            if (destination == OnrampDestination.ZCASH) {
                OnrampVisibleStep.CONVERTING_TO_ZEC.ordinal
            } else {
                BASE_STEP_COUNT
            }
        }

        OnrampPhase.EXPIRED -> {
            expiredIndex()
        }

        OnrampPhase.CANCELLED -> {
            OnrampVisibleStep.MERCHANT_MATCHED.ordinal
        }

        OnrampPhase.FAILED -> {
            OnrampVisibleStep.ORDER_PLACED.ordinal
        }
    }

/**
 * Only a delivered swap advances past the conversion row. A refund lands there too rather than one
 * row later: it is the conversion that did not complete, and completing that row before failing
 * "ZEC received" would claim a conversion that never happened.
 */
private fun OnrampZecDeliveryStatus.activeIndex(): Int =
    when (this) {
        is OnrampZecDeliveryStatus.Delivered -> OnrampVisibleStep.entries.size

        is OnrampZecDeliveryStatus.RefundedToBase,
        is OnrampZecDeliveryStatus.Failed,
        is OnrampZecDeliveryStatus.Preparing,
        is OnrampZecDeliveryStatus.Submitting,
        is OnrampZecDeliveryStatus.AwaitingZec,
        -> OnrampVisibleStep.CONVERTING_TO_ZEC.ordinal
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

/** A Base order's list ends at the USDC row; derived so inserting a step cannot silently truncate it. */
private val BASE_STEP_COUNT = OnrampVisibleStep.USDC_RECEIVED.ordinal + 1
