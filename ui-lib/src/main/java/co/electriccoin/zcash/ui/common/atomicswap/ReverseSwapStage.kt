// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.atomicswap

import co.electriccoin.zcash.ui.R
import xyz.justzappit.offramp.atomicswap.ReversePhase

@Suppress("CyclomaticComplexMethod")
internal fun ReversePhase?.label(): Int =
    when (this) {
        null, ReversePhase.QUOTED -> R.string.reverse_intro
        ReversePhase.ACCEPTING -> R.string.reverse_accepting
        ReversePhase.AWAITING_FUNDING -> R.string.reverse_review
        ReversePhase.SENDING_USDC -> R.string.reverse_sending
        ReversePhase.CONFIRMING_ESCROW -> R.string.reverse_confirming
        ReversePhase.RECEIVING_ZEC -> R.string.reverse_receiving
        ReversePhase.AWAITING_READY -> R.string.reverse_ready_explanation
        ReversePhase.SETTLING -> R.string.reverse_settling
        ReversePhase.RECEIVING -> R.string.reverse_sweeping
        ReversePhase.REFUND_WAIT -> R.string.reverse_refund_wait
        ReversePhase.REFUNDING -> R.string.reverse_refunding
        ReversePhase.REFUND_PAYOUT -> R.string.reverse_payout
        ReversePhase.COMPLETE -> R.string.reverse_complete
        ReversePhase.REFUNDED -> R.string.reverse_refunded
        ReversePhase.CANCELLED -> R.string.reverse_cancelled
    }
