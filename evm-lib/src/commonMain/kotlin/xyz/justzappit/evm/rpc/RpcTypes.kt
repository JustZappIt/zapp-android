// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.rpc

import kotlinx.serialization.Serializable
import xyz.justzappit.evm.types.Wei
import xyz.justzappit.evm.util.hexToBigInteger

@Serializable
data class TransactionReceipt(
    val transactionHash: String,
    val blockNumber: String,
    val status: String,
    val gasUsed: String,
    val effectiveGasPrice: String? = null,
    val contractAddress: String? = null,
    val logs: List<EvmLog> = emptyList(),
) {
    val success: Boolean get() = status == "0x1"
}

@Serializable
data class EvmLog(
    val address: String,
    val topics: List<String>,
    val data: String,
    val blockNumber: String,
    val transactionHash: String,
    val logIndex: String,
    val removed: Boolean = false,
)

@Serializable
data class UserOpGasPrice(
    val maxFeePerGas: String,
    val maxPriorityFeePerGas: String,
)

@Serializable
data class UserOpGasEstimate(
    val preVerificationGas: String,
    val verificationGasLimit: String,
    val callGasLimit: String,
)

/** Result of `pm_sponsorUserOperation`; for v0.6 only [paymasterAndData] is used. */
@Serializable
data class PaymasterResult(
    val paymasterAndData: String,
    val paymaster: String? = null,
)

@Serializable
data class BlockHeader(
    val number: String,
    val timestamp: String,
    val baseFeePerGas: String? = null,
) {
    /** The EIP-1559 base fee as a typed [Wei] amount, or null on a pre-EIP-1559 block. */
    val baseFee: Wei? get() = baseFeePerGas?.let { Wei(hexToBigInteger(it)) }
}
