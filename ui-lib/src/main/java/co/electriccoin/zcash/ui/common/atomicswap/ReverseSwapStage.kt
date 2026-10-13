// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.atomicswap

import androidx.annotation.StringRes
import co.electriccoin.zcash.ui.R
import xyz.justzappit.offramp.atomicswap.ReversePhase

/** What a reverse swap in this phase is doing, as its screens and notifications say it. */
@StringRes
internal fun ReversePhase?.label(): Int = PHASE_LABELS.getValue(this ?: ReversePhase.QUOTED)

internal val PHASE_LABELS: Map<ReversePhase, Int> =
    mapOf(
        ReversePhase.QUOTED to R.string.reverse_intro,
        ReversePhase.ACCEPTING to R.string.reverse_accepting,
        ReversePhase.AWAITING_FUNDING to R.string.reverse_review,
        ReversePhase.SENDING_USDC to R.string.reverse_sending,
        ReversePhase.CONFIRMING_ESCROW to R.string.reverse_confirming,
        ReversePhase.RECEIVING_ZEC to R.string.reverse_receiving,
        ReversePhase.AWAITING_READY to R.string.reverse_ready_explanation,
        ReversePhase.SETTLING to R.string.reverse_settling,
        ReversePhase.RECEIVING to R.string.reverse_sweeping,
        ReversePhase.REFUND_WAIT to R.string.reverse_refund_wait,
        ReversePhase.REFUNDING to R.string.reverse_refunding,
        ReversePhase.REFUND_PAYOUT to R.string.reverse_payout,
        ReversePhase.COMPLETE to R.string.reverse_complete,
        ReversePhase.REFUNDED to R.string.reverse_refunded,
        ReversePhase.CANCELLED to R.string.reverse_cancelled,
    )
