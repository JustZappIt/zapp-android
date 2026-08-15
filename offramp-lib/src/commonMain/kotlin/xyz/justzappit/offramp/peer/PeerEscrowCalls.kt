// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.peer

import xyz.justzappit.evm.abi.AbiBool
import xyz.justzappit.evm.abi.AbiEncoder
import xyz.justzappit.evm.abi.AbiUint
import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.offramp.p2p.Usdc6

/** Calldata builders for the four EscrowV2 calls the maker flow makes, plus the oracle read. */
object PeerEscrowCalls {
    const val CREATE_DEPOSIT_SIGNATURE: String =
        "createDeposit((address,uint256,(uint256,uint256),bytes32[],(address,bytes32,bytes)[]," +
            "(bytes32,uint256,(address,bytes,int16,uint32))[][],address,address,bool))"

    const val DEPOSIT_RECEIVED_SIGNATURE: String =
        "DepositReceived(uint256,address,address,uint256,(uint256,uint256),address,address)"

    fun createDepositCalldata(params: PeerDepositParams): ByteArray =
        AbiEncoder.encodeFunctionCall(CREATE_DEPOSIT_SIGNATURE, listOf(params.toAbiArg()))

    fun removeFundsCalldata(depositId: BigInteger, amount: Usdc6): ByteArray =
        AbiEncoder.encodeFunctionCall(
            "removeFunds(uint256,uint256)",
            listOf(AbiUint(depositId), AbiUint(amount.micros)),
        )

    fun pruneExpiredIntentsCalldata(depositId: BigInteger): ByteArray =
        AbiEncoder.encodeFunctionCall("pruneExpiredIntents(uint256)", listOf(AbiUint(depositId)))

    fun setAcceptingIntentsCalldata(depositId: BigInteger, accepting: Boolean): ByteArray =
        AbiEncoder.encodeFunctionCall(
            "setAcceptingIntents(uint256,bool)",
            listOf(AbiUint(depositId), AbiBool(accepting)),
        )

    fun latestRoundDataCalldata(): ByteArray =
        AbiEncoder.encodeFunctionCall("latestRoundData()", emptyList())
}
