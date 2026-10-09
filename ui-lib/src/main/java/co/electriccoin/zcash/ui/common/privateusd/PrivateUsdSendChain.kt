// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.privateusd

import xyz.justzappit.evm.abi.AbiBytes32
import xyz.justzappit.evm.abi.AbiDecoder
import xyz.justzappit.evm.abi.AbiEncoder
import xyz.justzappit.evm.abi.AbiUint
import xyz.justzappit.evm.rpc.BaseRpcClient
import xyz.justzappit.evm.rpc.TransactionStatus
import xyz.justzappit.evm.rpc.transactionStatus
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.evm.util.hexToBytes
import xyz.justzappit.railgun.RailgunNullifiers

/** How many of a proof's nullifiers are spent. One transact call spends all of its nullifiers or none of them. */
enum class PrivateUsdNullifiers {
    NONE,
    SOME,
    ALL,
}

/** Reads a relayed send's fate from the chain: its transaction, or the nullifiers it spends. */
class PrivateUsdSendChain(
    private val rpc: BaseRpcClient,
    private val railgunProxy: Address,
) {
    suspend fun status(txHash: TxHash): TransactionStatus = rpc.transactionStatus(txHash, CONFIRMATIONS)

    suspend fun spent(spends: List<RailgunNullifiers>): PrivateUsdNullifiers {
        val all = spends.flatMap { transaction -> transaction.nullifiers.map { transaction.tree to it } }
        val spent = all.count { (tree, nullifier) -> isSpent(tree, nullifier) }
        return when (spent) {
            0 -> PrivateUsdNullifiers.NONE
            all.size -> PrivateUsdNullifiers.ALL
            else -> PrivateUsdNullifiers.SOME
        }
    }

    private suspend fun isSpent(
        tree: Int,
        nullifier: String
    ): Boolean {
        val call =
            AbiEncoder.encodeFunctionCall(
                NULLIFIERS,
                listOf(AbiUint(tree.toBigInteger()), AbiBytes32(nullifier.hexToBytes())),
            )
        val answer = rpc.ethCall(railgunProxy, call)
        check(answer.size >= AbiDecoder.WORD) { "the node answered ${answer.size} bytes" }
        return AbiDecoder(answer).uint(0).signum() != 0
    }

    private companion object {
        const val CONFIRMATIONS = 1L
        const val NULLIFIERS = "nullifiers(uint256,bytes32)"
    }
}
