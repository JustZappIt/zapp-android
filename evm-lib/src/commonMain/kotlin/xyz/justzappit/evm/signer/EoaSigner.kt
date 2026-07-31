// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.signer

import kotlinx.coroutines.delay
import xyz.justzappit.evm.hd.EvmKey
import xyz.justzappit.evm.math.bigIntegerValueOf
import xyz.justzappit.evm.rpc.BaseRpcClient
import xyz.justzappit.evm.rpc.TransactionReceipt
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.types.ChainId
import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.evm.types.Wei
import xyz.justzappit.evm.util.toHex
import kotlin.time.TimeSource

class EoaSigner(
    private val rpc: BaseRpcClient,
    private val chainId: ChainId,
    private val account: EvmKey,
    private val baseFeeMultiplier: Int = DEFAULT_BASE_FEE_MULTIPLIER,
    private val gasLimitBufferPercent: Int = DEFAULT_GAS_BUFFER_PCT,
    private val receiptTimeoutMs: Long = DEFAULT_RECEIPT_TIMEOUT_MS,
    private val receiptPollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
) : TxSubmitter {
    override suspend fun sendTransaction(
        to: Address,
        value: Wei,
        data: ByteArray,
    ): TxHash {
        val nonce = rpc.ethGetTransactionCount(account.address, blockTag = "pending")
        val tip: Wei = rpc.ethMaxPriorityFeePerGas()
        val block = rpc.ethGetBlockByNumber(blockTag = "latest")
        val baseFee: Wei =
            block.baseFee
                ?: error("baseFeePerGas missing in latest block — chain may be pre-EIP-1559")
        val maxFee: Wei = baseFee * baseFeeMultiplier + tip
        val gasLimit =
            rpc
                .ethEstimateGas(account.address, to, value, data)
                .times(bigIntegerValueOf(100L + gasLimitBufferPercent))
                .div(bigIntegerValueOf(100L))

        val tx =
            Eip1559Tx(
                chainId = chainId,
                nonce = nonce,
                maxPriorityFeePerGas = tip,
                maxFeePerGas = maxFee,
                gasLimit = gasLimit,
                to = to,
                value = value,
                data = data,
            )
        val sig = account.signRecoverable(tx.signingHash())
        return rpc.ethSendRawTransaction("0x" + tx.encodeSigned(sig).toHex())
    }

    override suspend fun awaitReceipt(txHash: TxHash): TransactionReceipt {
        val started = TimeSource.Monotonic.markNow()
        while (started.elapsedNow().inWholeMilliseconds < receiptTimeoutMs) {
            rpc.ethGetTransactionReceipt(txHash)?.let { return it }
            delay(receiptPollIntervalMs)
        }
        error("Timed out after ${receiptTimeoutMs}ms waiting for receipt of ${txHash.hex}")
    }

    companion object {
        private const val DEFAULT_BASE_FEE_MULTIPLIER = 2
        private const val DEFAULT_GAS_BUFFER_PCT = 20
        private const val DEFAULT_RECEIPT_TIMEOUT_MS = 120_000L
        private const val DEFAULT_POLL_INTERVAL_MS = 2_000L
    }
}
