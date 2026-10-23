// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.atomicswap

import androidx.annotation.StringRes
import co.electriccoin.zcash.ui.R
import xyz.justzappit.offramp.atomicswap.AtomicSwapActivity
import xyz.justzappit.offramp.atomicswap.AtomicSwapWait

/** Where a swap under way stands, in the steps the screens and notifications name. */
enum class AtomicSwapStage(
    @param:StringRes val label: Int
) {
    OPENING(R.string.convert_step_open),
    DEPOSITING(R.string.convert_step_deposit),
    CONFIRMING(R.string.convert_step_confirm),
    CLAIMING(R.string.convert_step_claim),
    REFUNDING(R.string.convert_step_refund);

    companion object {
        fun of(state: AtomicSwapState): AtomicSwapStage =
            when {
                state.activity == AtomicSwapActivity.DEPOSITING -> DEPOSITING
                state.activity == AtomicSwapActivity.SWEEPING -> REFUNDING
                state.activity != null -> CLAIMING
                state.wait?.reason == AtomicSwapWait.OPENING -> OPENING
                state.wait?.reason in COMING_BACK -> REFUNDING
                state.wait?.reason in CLAIMS -> CLAIMING
                state.wait != null || state.record?.deposit?.txId != null -> CONFIRMING
                else -> OPENING
            }

        // Unwinding, the ZEC comes back with the maker's refund, or the claim goes for good after its late window.
        private val COMING_BACK = setOf(AtomicSwapWait.REFUNDING, AtomicSwapWait.UNWINDING)

        // The maker made the swap ready. One whose claim the relayer holds waits for confirmations, as it would.
        private val CLAIMS = setOf(AtomicSwapWait.READY_DEEPENING)
    }
}

/** What the progress notification says of a forward swap: its stage, or once the relayer holds its claim, that. */
@StringRes
fun AtomicSwapState.progressText(): Int =
    if (wait?.reason == AtomicSwapWait.CLAIM_HELD) R.string.convert_held_short else AtomicSwapStage.of(this).label
