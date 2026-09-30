// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.rpc

/** Where a transaction stands with a node. */
enum class TransactionStatus {
    /** Neither in a block nor waiting for one. */
    UNKNOWN,

    /** Waiting for a block, or in one with too few on top yet. */
    PENDING,
    CONFIRMED,
    REVERTED,
}
