// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.onramp

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import xyz.justzappit.offramp.onramp.FundsLocation
import xyz.justzappit.offramp.onramp.OnrampDestination
import xyz.justzappit.offramp.onramp.OnrampZecDeliveryStatus
import xyz.justzappit.offramp.p2p.Usdc6

internal fun completionTitle(state: OnrampState): StringResource =
    when {
        state.mode == OnrampMode.REFUNDED_TO_BASE -> stringRes(R.string.onramp_refunded_title)
        state.destination == OnrampDestination.ZCASH -> stringRes(R.string.onramp_zec_completion_title)
        else -> stringRes(R.string.onramp_completion_title)
    }

internal fun completionSubtitle(state: OnrampState): StringResource =
    when {
        state.mode == OnrampMode.REFUNDED_TO_BASE -> stringRes(R.string.onramp_refunded_subtitle)
        state.destination == OnrampDestination.ZCASH -> stringRes(R.string.onramp_zec_completion_subtitle)
        else -> stringRes(R.string.onramp_completion_subtitle)
    }

internal fun receivedAmount(state: OnrampState): String =
    when {
        state.mode == OnrampMode.REFUNDED_TO_BASE -> state.receivedUsdc?.plus(" USDC") ?: "—"
        state.destination == OnrampDestination.ZCASH -> state.receivedZec?.plus(" ZEC") ?: "—"
        else -> state.receivedUsdc?.plus(" USDC") ?: "—"
    }

internal fun deliveryFailureMessage(state: OnrampState): StringResource {
    val failure = state.delivery as? OnrampZecDeliveryStatus.Failed
    return when (failure?.fundsLocation) {
        FundsLocation.BASE_ACCOUNT -> stringRes(R.string.onramp_conversion_failed_on_base)
        FundsLocation.RECIPIENT_MISMATCH -> stringRes(R.string.onramp_conversion_recipient_mismatch)
        else -> stringRes(R.string.onramp_conversion_status_uncertain)
    }
}

internal fun OnrampState.withDeliveryStatus(
    status: OnrampZecDeliveryStatus,
    transactionUrl: (String) -> String,
): OnrampState =
    when (status) {
        is OnrampZecDeliveryStatus.Delivered -> {
            copy(
                mode = OnrampMode.COMPLETION,
                delivery = status,
                receivedZec = status.outputZec,
                transactionExplorerUrl = status.baseTransactionHash?.let(transactionUrl),
                error = null,
            )
        }

        is OnrampZecDeliveryStatus.RefundedToBase -> {
            copy(
                mode = OnrampMode.REFUNDED_TO_BASE,
                delivery = status,
                receivedUsdc = status.refundedUsdc.toDisplayString(stripTrailingZeros = true),
                transactionExplorerUrl = null,
                error = null,
            )
        }

        is OnrampZecDeliveryStatus.Failed -> {
            copy(
                mode = OnrampMode.DELIVERY_NEEDS_ATTENTION,
                delivery = status,
                error =
                    when (status.fundsLocation) {
                        FundsLocation.BASE_ACCOUNT -> stringRes(R.string.onramp_conversion_failed_on_base)
                        FundsLocation.RECIPIENT_MISMATCH -> stringRes(R.string.onramp_conversion_recipient_mismatch)
                        else -> stringRes(R.string.onramp_conversion_status_uncertain)
                    },
            )
        }

        is OnrampZecDeliveryStatus.Preparing,
        is OnrampZecDeliveryStatus.Submitting,
        is OnrampZecDeliveryStatus.AwaitingZec,
        -> {
            copy(mode = OnrampMode.CONVERTING_TO_ZEC, delivery = status, error = null)
        }
    }
