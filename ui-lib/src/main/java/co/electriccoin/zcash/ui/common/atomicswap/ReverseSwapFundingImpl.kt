// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.atomicswap

import co.electriccoin.zcash.ui.common.repository.RailgunWalletRepository
import xyz.justzappit.evm.rpc.BaseRpcClient
import xyz.justzappit.evm.rpc.sendSignedTransaction
import xyz.justzappit.evm.util.toHex
import xyz.justzappit.offramp.atomicswap.ReverseFundingCalls
import xyz.justzappit.offramp.atomicswap.ReverseFundingCost
import xyz.justzappit.offramp.atomicswap.ReverseFundingTransaction
import xyz.justzappit.offramp.atomicswap.ReverseSwapFunding
import xyz.justzappit.offramp.atomicswap.ReverseSwapRecord
import xyz.justzappit.offramp.atomicswap.SwapDeployment
import xyz.justzappit.offramp.p2p.Usdc6
import xyz.justzappit.railgun.RailgunContractCall
import xyz.justzappit.railgun.RailgunReverseCost
import xyz.justzappit.railgun.RailgunReverseCostRequest
import xyz.justzappit.railgun.RailgunReverseRequest

/** Funds a reverse swap's escrow from the private balance through Railgun, and sends the signed transaction. */
class ReverseSwapFundingImpl(
    private val wallet: RailgunWalletRepository,
    private val rpc: BaseRpcClient,
    private val deployment: SwapDeployment,
) : ReverseSwapFunding {
    override suspend fun cost(escrow: Usdc6): ReverseFundingCost =
        wallet.reverseCost(RailgunReverseCostRequest(escrow.micros, deployment.railgunProxy)).toSwapCost()

    override suspend fun prepare(
        record: ReverseSwapRecord,
        signature: ByteArray
    ): ReverseFundingTransaction {
        check(record.deployment == deployment) { "the conversion belongs to another deployment" }
        val request =
            RailgunReverseRequest(
                record.quote.terms.amount.micros,
                deployment.railgunProxy,
                deployment.token,
                ReverseFundingCalls.encode(record, signature).map { (to, data) ->
                    RailgunContractCall(to, "0x" + data.toHex())
                },
            )
        val transaction = wallet.prepareReverse(request)
        return ReverseFundingTransaction(transaction.raw, transaction.txId, transaction.cost.toSwapCost())
    }

    override suspend fun submit(transaction: ReverseFundingTransaction) =
        rpc.sendSignedTransaction(transaction.raw, transaction.txId)

    private fun RailgunReverseCost.toSwapCost() = ReverseFundingCost(Usdc6(debit), Usdc6(railgunFee), null)
}
