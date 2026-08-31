// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.signer

import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.rpc.TransactionReceipt
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.evm.types.Wei

/** The deterministic identity and nonce of transaction bytes that are ready to broadcast. */
data class PreparedTransaction(
    val hash: TxHash,
    val nonce: BigInteger,
)

/**
 * The orchestrator's only coupling to how a call reaches the chain. An EOA signs and broadcasts a
 * raw tx; the ERC-4337 path wraps the same `{to, value, data}` in a sponsored UserOperation. Both
 * return a 32-byte handle — a tx hash for the EOA, a userOpHash for 4337 — that [awaitReceipt]
 * resolves to the mined [TransactionReceipt] (its inner logs are identical, so receipt log parsing
 * is unaffected by which path produced it).
 */
interface TxSubmitter {
    /**
     * Builds and signs the transaction before invoking [beforeBroadcast], then sends those exact
     * bytes. The callback's identity is therefore durable even if the RPC send loses its response.
     * If preparation or the callback fails, nothing has been broadcast.
     */
    suspend fun sendTransaction(
        to: Address,
        value: Wei = Wei.ZERO,
        data: ByteArray = byteArrayOf(),
        beforeBroadcast: suspend (PreparedTransaction) -> Unit = {},
    ): TxHash

    /**
     * Restores ownership recorded before a previous process died. A null hash/nonce is a legacy
     * unresolved send and must block replacement until an external recovery decision is made.
     */
    suspend fun restorePendingTransaction(hash: TxHash?, nonce: BigInteger?)

    /** One non-polling receipt lookup, used by cold-start reconciliation. */
    suspend fun receiptIfIncluded(txHash: TxHash): TransactionReceipt?

    suspend fun awaitReceipt(txHash: TxHash): TransactionReceipt
}
