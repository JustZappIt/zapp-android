// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.rpc

import xyz.justzappit.evm.abi.keccak256
import xyz.justzappit.evm.math.toNonNegativeLongExact
import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.evm.util.hexToBigInteger
import xyz.justzappit.evm.util.hexToBytes

/**
 * Sends [raw], the signed transaction [txHash] names. A node that has it already may refuse it again, so a
 * refusal stands only while the node doesn't know the hash: sending it twice is sending it once.
 */
suspend fun BaseRpcClient.sendSignedTransaction(
    raw: String,
    txHash: TxHash,
) {
    require(keccak256(raw.hexToBytes()).contentEquals(txHash.bytes)) { "the transaction isn't the one its hash names" }
    try {
        check(ethSendRawTransaction(raw) == txHash) { "the node took another transaction" }
    } catch (e: RpcException) {
        if (ethGetTransactionByHash(txHash) == null) throw e
    }
}

/** [txHash] as the node sees it, counted as in a block once that block has [confirmations] including itself. */
suspend fun BaseRpcClient.transactionStatus(
    txHash: TxHash,
    confirmations: Long,
): TransactionStatus {
    require(confirmations > 0) { "confirmation depth must be positive" }
    val receipt = ethGetTransactionReceipt(txHash) ?: return waitingOrUnknown(txHash)
    val head = hexToBigInteger(ethGetBlockByNumber().number).toNonNegativeLongExact()
    val included = hexToBigInteger(receipt.blockNumber).toNonNegativeLongExact()
    return when {
        included > head || head - included < confirmations - 1 -> TransactionStatus.PENDING
        hexToBigInteger(receipt.status).signum() == 0 -> TransactionStatus.REVERTED
        else -> TransactionStatus.CONFIRMED
    }
}

private suspend fun BaseRpcClient.waitingOrUnknown(txHash: TxHash): TransactionStatus =
    if (ethGetTransactionByHash(txHash) == null) TransactionStatus.UNKNOWN else TransactionStatus.PENDING
