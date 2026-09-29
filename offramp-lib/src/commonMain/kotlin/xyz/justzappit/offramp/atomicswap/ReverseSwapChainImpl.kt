// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.atomicswap

import xyz.justzappit.evm.abi.AbiAddress
import xyz.justzappit.evm.abi.AbiBytes32
import xyz.justzappit.evm.abi.AbiDecoder
import xyz.justzappit.evm.abi.AbiEncoder
import xyz.justzappit.evm.rpc.BaseRpcClient
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.evm.util.hexToBigInteger
import kotlin.time.Clock

class ReverseSwapChainImpl(
    private val rpc: BaseRpcClient,
    private val deployment: ReverseDeployment
) : ReverseSwapChain {
    private val contract = Address.parse(deployment.contract)

    override suspend fun read(id: String): ReverseChainState {
        check(rpc.ethChainId().value == deployment.chainId)
        val head = rpc.ethGetBlockByNumber()
        val timestamp = hexToBigInteger(head.timestamp).toLong()
        check(Clock.System.now().epochSeconds - timestamp in -MAX_FUTURE_SECONDS..MAX_STALE_SECONDS) {
            "settlement chain is stale"
        }
        val block = hexToBigInteger(head.number).toLong()
        val tag = "0x" + (block - deployment.confirmations + 1).coerceAtLeast(0).toString(HEX_RADIX)
        val args = listOf(AbiBytes32(fixedHex(id, SWAP_WORD_BYTES)))
        val swap =
            AtomicSwapChain.decodeSwap(
                rpc.ethCall(contract, AbiEncoder.encodeFunctionCall("getSwap(bytes32)", args), tag)
            )
        val funding =
            AbiDecoder(rpc.ethCall(contract, AbiEncoder.encodeFunctionCall("reverseFunding(bytes32)", args), tag))
        funding.requireWords(2)
        val duration =
            AbiDecoder(rpc.ethCall(contract, AbiEncoder.encodeFunctionCall("LOCK_DURATION()", emptyList()), tag))
        return ReverseChainState(
            swap,
            funding.word(0),
            funding.uint(1).toLong(),
            block,
            hexToBigInteger(head.timestamp).toLong(),
            duration.uint(0).toLong()
        )
    }

    override suspend fun fundingStatus(txId: String): ReverseTransactionStatus {
        val hash = TxHash.fromHex(txId)
        val receipt =
            rpc.ethGetTransactionReceipt(hash)
                ?: return if (rpc.ethGetTransactionByHash(hash) != null) {
                    ReverseTransactionStatus.PENDING
                } else {
                    ReverseTransactionStatus.UNKNOWN
                }
        val latest = hexToBigInteger(rpc.ethGetBlockByNumber().number).toLong()
        return if (latest - hexToBigInteger(receipt.blockNumber).toLong() + 1 >= deployment.confirmations) {
            if (hexToBigInteger(receipt.status).toInt() ==
                0
            ) {
                ReverseTransactionStatus.REVERTED
            } else {
                ReverseTransactionStatus.CONFIRMED
            }
        } else {
            ReverseTransactionStatus.PENDING
        }
    }

    override suspend fun vaultBalance(id: String): String {
        val vault =
            AbiDecoder(
                rpc.ethCall(
                    contract,
                    AbiEncoder.encodeFunctionCall("vaultOf(bytes32)", listOf(AbiBytes32(fixedHex(id, SWAP_WORD_BYTES))))
                )
            ).address(0)
        return AbiDecoder(
            rpc.ethCall(
                Address.parse(deployment.token),
                AbiEncoder.encodeFunctionCall("balanceOf(address)", listOf(AbiAddress(vault)))
            )
        ).uint(0).toString()
    }
}

private const val MAX_FUTURE_SECONDS = 30L
private const val MAX_STALE_SECONDS = 90L

private const val HEX_RADIX = 16
