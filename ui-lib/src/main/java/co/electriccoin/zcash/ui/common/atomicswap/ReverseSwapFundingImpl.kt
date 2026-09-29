// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.atomicswap

import co.electriccoin.zcash.ui.common.repository.RailgunWalletRepository
import xyz.justzappit.evm.abi.keccak256
import xyz.justzappit.evm.rpc.BaseRpcClient
import xyz.justzappit.evm.rpc.RpcException
import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.evm.util.hexToBytes
import xyz.justzappit.evm.util.toHex
import xyz.justzappit.offramp.atomicswap.ReverseDeployment
import xyz.justzappit.offramp.atomicswap.ReverseFundingCalls
import xyz.justzappit.offramp.atomicswap.ReverseFundingCost
import xyz.justzappit.offramp.atomicswap.ReverseFundingTransaction
import xyz.justzappit.offramp.atomicswap.ReverseSwapFunding
import xyz.justzappit.offramp.atomicswap.ReverseSwapRecord
import xyz.justzappit.offramp.atomicswap.fixedHex
import xyz.justzappit.railgun.RailgunContractCall
import xyz.justzappit.railgun.RailgunReverseCost
import xyz.justzappit.railgun.RailgunReverseCostRequest
import xyz.justzappit.railgun.RailgunReverseRequest

class ReverseSwapFundingImpl(
    private val wallet: RailgunWalletRepository,
    private val rpc: BaseRpcClient,
    private val deployment: ReverseDeployment,
) : ReverseSwapFunding {
    override suspend fun cost(escrowAmount: String): ReverseFundingCost =
        wallet.reverseCost(RailgunReverseCostRequest(escrowAmount, deployment.railgun)).toSwapCost()

    override suspend fun prepare(record: ReverseSwapRecord, signature: ByteArray): ReverseFundingTransaction {
        check(record.deployment == deployment)
        val request =
            RailgunReverseRequest(
                record.quote.terms.amount,
                deployment.railgun,
                deployment.token,
                ReverseFundingCalls.encode(record, signature).map { (to, data) ->
                    RailgunContractCall(
                        to,
                        "0x" + data.toHex()
                    )
                }
            )
        val transaction = wallet.prepareReverse(request)
        check(keccak256(transaction.raw.hexToBytes()).contentEquals(fixedHex(transaction.txId, HASH_BYTES)))
        return ReverseFundingTransaction(transaction.raw, transaction.txId, transaction.cost.toSwapCost())
    }

    override suspend fun submit(transaction: ReverseFundingTransaction) {
        check(keccak256(transaction.raw.hexToBytes()).contentEquals(fixedHex(transaction.txId, HASH_BYTES)))
        try {
            check(rpc.ethSendRawTransaction(transaction.raw).hex.equals(transaction.txId, ignoreCase = true))
        } catch (e: RpcException) {
            if (rpc.ethGetTransactionByHash(TxHash.fromHex(transaction.txId)) == null) throw e
        }
    }

    private fun RailgunReverseCost.toSwapCost() = ReverseFundingCost(debit, railgunFee, broadcasterFee)

    private companion object {
        const val HASH_BYTES = 32
    }
}
