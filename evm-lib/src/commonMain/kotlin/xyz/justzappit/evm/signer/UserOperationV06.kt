// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.signer

import xyz.justzappit.evm.abi.AbiAddress
import xyz.justzappit.evm.abi.AbiBytes32
import xyz.justzappit.evm.abi.AbiEncoder
import xyz.justzappit.evm.abi.AbiUint
import xyz.justzappit.evm.abi.keccak256
import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.math.bigIntegerValueOf
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.types.ChainId

/**
 * An ERC-4337 v0.6 UserOperation. [signature] is excluded from [userOpHash] by design: the hash is
 * the message the owner key signs, so it has to be computable before the signature exists.
 */
data class UserOperationV06(
    val sender: Address,
    val nonce: BigInteger,
    val initCode: ByteArray,
    val callData: ByteArray,
    val callGasLimit: BigInteger,
    val verificationGasLimit: BigInteger,
    val preVerificationGas: BigInteger,
    val maxFeePerGas: BigInteger,
    val maxPriorityFeePerGas: BigInteger,
    val paymasterAndData: ByteArray,
    val signature: ByteArray = ByteArray(0),
) {
    fun userOpHash(entryPoint: Address, chainId: ChainId): ByteArray {
        val packed =
            AbiEncoder.encode(
                listOf(
                    AbiAddress(sender),
                    AbiUint(nonce),
                    AbiBytes32(keccak256(initCode)),
                    AbiBytes32(keccak256(callData)),
                    AbiUint(callGasLimit),
                    AbiUint(verificationGasLimit),
                    AbiUint(preVerificationGas),
                    AbiUint(maxFeePerGas),
                    AbiUint(maxPriorityFeePerGas),
                    AbiBytes32(keccak256(paymasterAndData)),
                ),
            )
        return keccak256(
            AbiEncoder.encode(
                listOf(
                    AbiBytes32(keccak256(packed)),
                    AbiAddress(entryPoint),
                    AbiUint(bigIntegerValueOf(chainId.value)),
                ),
            ),
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UserOperationV06) return false
        return sender == other.sender &&
            nonce == other.nonce &&
            initCode.contentEquals(other.initCode) &&
            callData.contentEquals(other.callData) &&
            callGasLimit == other.callGasLimit &&
            verificationGasLimit == other.verificationGasLimit &&
            preVerificationGas == other.preVerificationGas &&
            maxFeePerGas == other.maxFeePerGas &&
            maxPriorityFeePerGas == other.maxPriorityFeePerGas &&
            paymasterAndData.contentEquals(other.paymasterAndData) &&
            signature.contentEquals(other.signature)
    }

    override fun hashCode(): Int {
        var h = sender.hashCode()
        h = 31 * h + nonce.hashCode()
        h = 31 * h + initCode.contentHashCode()
        h = 31 * h + callData.contentHashCode()
        h = 31 * h + callGasLimit.hashCode()
        h = 31 * h + verificationGasLimit.hashCode()
        h = 31 * h + preVerificationGas.hashCode()
        h = 31 * h + maxFeePerGas.hashCode()
        h = 31 * h + maxPriorityFeePerGas.hashCode()
        h = 31 * h + paymasterAndData.contentHashCode()
        h = 31 * h + signature.contentHashCode()
        return h
    }
}
