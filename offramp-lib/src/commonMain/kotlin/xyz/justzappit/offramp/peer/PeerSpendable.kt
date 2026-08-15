// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.peer

import xyz.justzappit.offramp.p2p.Usdc6

/**
 * What the user may actually commit right now: the Base USDC balance less everything earlier
 * attempts have promised and not yet escrowed. An amount is not gone from the account until
 * `createDeposit` is mined, so the raw balance says it is still there.
 *
 * A failed read is [Unavailable] rather than a missing balance: as a null the two are the same
 * value, and the amount screen has to answer them differently.
 */
sealed interface PeerSpendable {
    data object Loading : PeerSpendable

    data object Unavailable : PeerSpendable

    data class Ready(
        val baseBalance: Usdc6,
        val committed: Usdc6,
    ) : PeerSpendable {
        val available: Usdc6 get() = maxOf(Usdc6.ZERO, baseBalance - committed)

        val hasCommitment: Boolean get() = committed > Usdc6.ZERO

        fun covers(amount: Usdc6): Boolean = amount <= available
    }
}
