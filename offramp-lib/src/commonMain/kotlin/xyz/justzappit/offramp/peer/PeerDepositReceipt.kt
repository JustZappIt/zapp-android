// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.peer

import xyz.justzappit.evm.abi.keccak256
import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.rpc.TransactionReceipt
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.util.hexToBytes
import xyz.justzappit.evm.util.toHex

/**
 * Reads the deposit id out of a `createDeposit` receipt. The id comes from the log, not the
 * indexer: right after the transaction the indexer has not seen the deposit yet, and treating that
 * gap as "order not found" is how a live order renders as a failure.
 */
object PeerDepositReceipt {
    val DEPOSIT_RECEIVED_TOPIC: String =
        "0x" + keccak256(PeerEscrowCalls.DEPOSIT_RECEIVED_SIGNATURE.encodeToByteArray()).toHex()

    fun depositIdFrom(receipt: TransactionReceipt, escrow: Address): PeerDepositId? =
        receipt.logs
            .firstOrNull { log ->
                Address.parseOrNull(log.address) == escrow &&
                    log.topics.firstOrNull()?.equals(DEPOSIT_RECEIVED_TOPIC, ignoreCase = true) == true
            }?.topics
            ?.getOrNull(TOPIC_DEPOSIT_ID)
            ?.let { runCatching { BigInteger(1, it.hexToBytes()) }.getOrNull() }
            ?.let { PeerDepositId.of(escrow, it) }

    private const val TOPIC_DEPOSIT_ID = 1
}
