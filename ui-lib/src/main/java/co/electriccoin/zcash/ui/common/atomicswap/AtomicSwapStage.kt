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
                state.wait != null || state.record?.depositTxId != null -> CONFIRMING
                else -> OPENING
            }
    }
}
