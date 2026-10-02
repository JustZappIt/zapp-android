// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.privateusd

import co.electriccoin.zcash.spackle.Twig
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import xyz.justzappit.evm.rpc.BaseRpcClient
import xyz.justzappit.evm.rpc.RpcException
import xyz.justzappit.evm.rpc.TransactionStatus
import xyz.justzappit.evm.rpc.sendSignedTransaction
import xyz.justzappit.evm.rpc.transactionStatus
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.types.TxHash
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

enum class GasAccountDelivery {
    /** In a block. */
    CONFIRMED,

    /** Sent, or maybe sent, and not in a block yet: it may still land, so it's kept to send again. */
    UNCONFIRMED,

    /** Reverted on chain: nothing moved. */
    FAILED,
}

/** Sends what the Sepolia gas account signed, and follows it to its block. Sending one twice is sending it once. */
class GasAccountTransactions(
    private val rpc: BaseRpcClient,
) {
    /** Sends [raw] and waits a while for its block. */
    suspend fun deliver(
        raw: String,
        txHash: TxHash,
    ): GasAccountDelivery {
        send(raw, txHash)?.let { return it }
        return withTimeoutOrNull(CONFIRM_TIMEOUT) { awaitBlock(txHash) } ?: GasAccountDelivery.UNCONFIRMED
    }

    /** One the node lost is sent again while [nonce] is free; only its receipt settles it. */
    suspend fun reconcile(
        raw: String,
        txHash: TxHash,
        from: Address,
        nonce: Long,
    ): GasAccountDelivery {
        // The nonce first: one mined after it was read still shows in the status read after.
        val isNonceTaken = rpc.ethGetTransactionCount(from, LATEST).value > nonce.toBigInteger()
        val status = rpc.statusOf(txHash)
        return when {
            status != TransactionStatus.UNKNOWN -> status.delivery()
            isNonceTaken -> GasAccountDelivery.UNCONFIRMED
            else -> send(raw, txHash) ?: GasAccountDelivery.UNCONFIRMED
        }
    }

    /** Where a send an earlier build logged without its transaction stands; it can't be sent again. */
    suspend fun statusOf(txHash: TxHash): GasAccountDelivery = rpc.statusOf(txHash).delivery()

    // A node's error is not proof of rejection: another node may already have accepted the transaction.
    private suspend fun send(
        raw: String,
        txHash: TxHash
    ): GasAccountDelivery? =
        try {
            rpc.sendSignedTransaction(raw, txHash)
            null
        } catch (e: RpcException.TransportError) {
            Twig.warn(e) { "Private USD: $txHash may not have reached the node" }
            GasAccountDelivery.UNCONFIRMED
        } catch (e: RpcException.RateLimited) {
            Twig.warn(e) { "Private USD: $txHash wasn't taken yet" }
            GasAccountDelivery.UNCONFIRMED
        } catch (e: RpcException) {
            Twig.warn(e) { "Private USD: $txHash's broadcast is unresolved" }
            GasAccountDelivery.UNCONFIRMED
        }

    private suspend fun awaitBlock(txHash: TxHash): GasAccountDelivery {
        while (true) {
            when (pollStatus(txHash)) {
                TransactionStatus.CONFIRMED -> return GasAccountDelivery.CONFIRMED
                TransactionStatus.REVERTED -> return GasAccountDelivery.FAILED
                else -> delay(CONFIRM_POLL)
            }
        }
    }

    // A poll the node didn't answer is just a poll missed.
    private suspend fun pollStatus(txHash: TxHash): TransactionStatus? =
        try {
            rpc.statusOf(txHash)
        } catch (e: RpcException) {
            Twig.warn(e) { "Private USD: $txHash's status is unknown for now" }
            null
        }

    private suspend fun BaseRpcClient.statusOf(txHash: TxHash) = transactionStatus(txHash, CONFIRMATIONS)

    private fun TransactionStatus.delivery() =
        when (this) {
            TransactionStatus.CONFIRMED -> GasAccountDelivery.CONFIRMED
            TransactionStatus.REVERTED -> GasAccountDelivery.FAILED
            TransactionStatus.PENDING, TransactionStatus.UNKNOWN -> GasAccountDelivery.UNCONFIRMED
        }

    private companion object {
        const val CONFIRMATIONS = 1L
        const val LATEST = "latest"
        val CONFIRM_TIMEOUT = 2.minutes
        val CONFIRM_POLL = 4.seconds
    }
}
