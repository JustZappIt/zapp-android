// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.atomicswap

import kotlinx.serialization.Serializable

/**
 * The windows contract's two window lengths in seconds, fixed when it was deployed. At every moment at most one side
 * of a swap may reveal its share, by the clock and the swap's stage alone.
 */
@Serializable
data class SwapWindows(
    /** `MIN_WINDOW()`: the least an open leaves before `t0`, and from `t0` to `t1`. */
    val min: Long,
    /** `LATE_WINDOW()`: how long from `t1` only the maker may refund a forward swap; after it, its user may claim. */
    val late: Long,
) {
    init {
        require(min > 0 && late > 0) { "a window lasts" }
    }
}
