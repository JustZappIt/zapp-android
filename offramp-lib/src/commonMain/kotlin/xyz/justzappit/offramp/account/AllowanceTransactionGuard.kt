// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.account

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serialises an exact ERC-20 approval with the transaction that consumes it.
 *
 * ERC-20 `approve` replaces rather than adds to the allowance. Sharing only the submitter's nonce
 * lock still permits `approve(A), approve(B), spend(A)`, where the second approval makes the first
 * spend revert. One instance belongs to one submitting account and is shared by every rail that
 * spends its Base USDC.
 */
class AllowanceTransactionGuard {
    private val mutex = Mutex()

    suspend fun <T> withApprovalAndSpend(block: suspend () -> T): T = mutex.withLock { block() }
}
