// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.onramp

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import xyz.justzappit.offramp.onramp.OnrampFailureCode
import xyz.justzappit.offramp.onramp.OnrampPhase

/**
 * The sentence a failure gets, chosen by the phase as well as the code.
 *
 * ☠ The unreachable codes are the reason the phase is here. [OnrampFailureCode.UPSTREAM_FAILED] is
 * what a dropped `paidBuyOrder` becomes, and its "your money has not moved" is false exactly when
 * it is most expensive: the reasonable response to it is to pay the merchant a second time.
 *
 * [phase] is null where no order exists yet — a quote that would not price.
 */
@Suppress("CyclomaticComplexMethod")
internal fun onrampFailureMessage(code: OnrampFailureCode, phase: OnrampPhase?): StringResource =
    when (code) {
        OnrampFailureCode.BAD_REQUEST -> {
            stringRes(R.string.onramp_error_limits)
        }

        OnrampFailureCode.UNAUTHENTICATED,
        OnrampFailureCode.NONCE_INVALID,
        -> {
            stringRes(R.string.onramp_error_unauthenticated)
        }

        OnrampFailureCode.RECIPIENT_NOT_ALLOWED -> {
            stringRes(R.string.onramp_error_recipient_not_allowed)
        }

        OnrampFailureCode.ROUTE_DISABLED -> {
            stringRes(R.string.onramp_error_corridor_disabled)
        }

        OnrampFailureCode.ORDER_NOT_FOUND -> {
            stringRes(R.string.onramp_error_order_not_found)
        }

        OnrampFailureCode.WRONG_PHASE -> {
            stringRes(R.string.onramp_error_wrong_phase)
        }

        OnrampFailureCode.QUOTE_EXPIRED -> {
            stringRes(R.string.onramp_error_quote_expired)
        }

        OnrampFailureCode.CAP_EXCEEDED -> {
            stringRes(R.string.onramp_error_cap_exceeded)
        }

        OnrampFailureCode.DAILY_LIMIT_EXCEEDED -> {
            stringRes(R.string.onramp_error_daily_limit)
        }

        OnrampFailureCode.VOLUME_LIMIT_EXCEEDED -> {
            stringRes(R.string.onramp_error_volume_limit)
        }

        OnrampFailureCode.USER_BLACKLISTED -> {
            stringRes(R.string.onramp_error_blacklisted)
        }

        OnrampFailureCode.SCREENING_REJECTED -> {
            stringRes(R.string.onramp_error_screening_rejected)
        }

        OnrampFailureCode.UPSTREAM_FAILED,
        OnrampFailureCode.OPERATOR_UNAVAILABLE,
        OnrampFailureCode.NETWORK_UNAVAILABLE,
        -> {
            if (phase?.hasSentFiat == true) {
                stringRes(R.string.onramp_error_settlement_pending)
            } else {
                stringRes(R.string.onramp_error_backend_unavailable)
            }
        }

        OnrampFailureCode.NO_MERCHANT -> {
            stringRes(R.string.onramp_error_no_merchant)
        }

        OnrampFailureCode.ORDER_EXPIRED -> {
            stringRes(R.string.onramp_error_order_expired)
        }

        OnrampFailureCode.SETTLEMENT_PENDING -> {
            stringRes(R.string.onramp_error_settlement_pending)
        }

        OnrampFailureCode.UNKNOWN -> {
            stringRes(R.string.onramp_error_progress)
        }
    }
