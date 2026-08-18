// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.signer

import xyz.justzappit.evm.rpc.TransactionReceipt
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.evm.types.Wei

/**
 * The orchestrator's only coupling to how a call reaches the chain. An EOA signs and broadcasts a
 * raw tx; the ERC-4337 path wraps the same `{to, value, data}` in a sponsored UserOperation. Both
 * return a 32-byte handle — a tx hash for the EOA, a userOpHash for 4337 — that [awaitReceipt]
 * resolves to the mined [TransactionReceipt] (its inner logs are identical, so receipt log parsing
 * is unaffected by which path produced it).
 */
interface TxSubmitter {
    suspend fun sendTransaction(
        to: Address,
        value: Wei = Wei.ZERO,
        data: ByteArray = byteArrayOf(),
    ): TxHash

    suspend fun awaitReceipt(txHash: TxHash): TransactionReceipt
}
