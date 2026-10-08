// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.atomicswap

import co.electriccoin.zcash.ui.common.privateusd.Sepolia
import co.electriccoin.zcash.ui.common.repository.RailgunWalletRepository
import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.evm.util.toHex
import xyz.justzappit.offramp.atomicswap.ReverseFundingCalls
import xyz.justzappit.offramp.atomicswap.ReverseFundingCost
import xyz.justzappit.offramp.atomicswap.ReverseFundingRequest
import xyz.justzappit.offramp.atomicswap.ReverseFundingTerms
import xyz.justzappit.offramp.atomicswap.ReverseFundingTransaction
import xyz.justzappit.offramp.atomicswap.ReverseSwapFunding
import xyz.justzappit.offramp.atomicswap.ReverseSwapRecord
import xyz.justzappit.offramp.atomicswap.SwapDeployment
import xyz.justzappit.offramp.atomicswap.SwapRelayer
import xyz.justzappit.offramp.atomicswap.requireReverseFunding
import xyz.justzappit.offramp.p2p.Usdc6
import xyz.justzappit.railgun.RailgunContractCall
import xyz.justzappit.railgun.RailgunReverseCost
import xyz.justzappit.railgun.RailgunReverseCostRequest
import xyz.justzappit.railgun.RailgunReverseRequest

/** Proves funding locally, paying the pinned relayer its fee for gas; it submits the exact persisted calldata. */
class ReverseSwapFundingImpl(
    private val wallet: RailgunWalletRepository,
    private val deployment: SwapDeployment,
    private val relayer: SwapRelayer,
) : ReverseSwapFunding {
    override suspend fun fee(): Usdc6 = sponsoredTerms().chargedFee

    override suspend fun cost(escrow: Usdc6): ReverseFundingCost {
        val fee = sponsoredTerms().chargedFee
        return wallet
            .reverseCost(RailgunReverseCostRequest(escrow.micros, deployment.railgunProxy, fee.micros))
            .toSwapCost(fee)
    }

    override suspend fun prepare(
        record: ReverseSwapRecord,
        signature: ByteArray
    ): ReverseFundingTransaction {
        check(record.deployment == deployment) { "the conversion belongs to another deployment" }
        val terms = sponsoredTerms()
        val fee = terms.chargedFee
        val request =
            RailgunReverseRequest(
                record.quote.terms.amount.micros,
                deployment.railgunProxy,
                deployment.token,
                ReverseFundingCalls.encode(record, signature, fee).map { (to, data) ->
                    RailgunContractCall(to, "0x" + data.toHex())
                },
                terms.relayAdapt,
                fee.micros,
            )
        val transaction = wallet.prepareReverse(request)
        check(transaction.to == terms.relayAdapt && transaction.value.signum() == 0) {
            "unexpected funding destination"
        }
        check((transaction.data.length - 2) / 2 <= terms.maxCalldataBytes) {
            "funding exceeds the relayer's calldata limit"
        }
        return ReverseFundingTransaction(
            cost = transaction.cost.toSwapCost(fee),
            request = ReverseFundingRequest(record.swapId, deployment.chainId, transaction.to, transaction.data, "0"),
        )
    }

    override suspend fun submit(transaction: ReverseFundingTransaction): TxHash? {
        val request = transaction.request
        val terms = sponsoredTerms()
        check(request.chainId == deployment.chainId && request.to == terms.relayAdapt) {
            "unexpected funding deployment"
        }
        check((request.data.length - 2) / 2 <= terms.maxCalldataBytes) {
            "funding exceeds the relayer's calldata limit"
        }
        val sent = relayer.fundReverse(request)
        check(sent.transactions.size <= 1) { "funding returned multiple transactions" }
        return sent.transactions.singleOrNull()
    }

    private suspend fun sponsoredTerms() = relayer.terms().requireReverseFunding(deployment, Sepolia.RELAY_ADAPT)

    private val ReverseFundingTerms.chargedFee get() = checkNotNull(fee) { "the relayer charges no funding fee" }

    private fun RailgunReverseCost.toSwapCost(fee: Usdc6) = ReverseFundingCost(Usdc6(debit), Usdc6(railgunFee), fee)
}
