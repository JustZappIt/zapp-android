// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.p2p

import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.types.TxHash

data class OrderSnapshot(
    val orderId: BigInteger,
    val status: OrderStatus,
    val orderType: OrderType,
    val circleId: BigInteger,
    val userAddress: Address,
    val usdcAmount: Usdc6,
    val fiatAmount: Usdc6,
    val currencyHex: String,
    val acceptedMerchantAddress: Address?,
    val merchantPubKey: String,
    val encryptedUserUpi: String,
    val encryptedMerchantUpi: String,
    val placedAtEpochSeconds: Long?,
    val acceptedAtEpochSeconds: Long?,
    val paidAtEpochSeconds: Long?,
    val completedAtEpochSeconds: Long?,
    val cancelledAtEpochSeconds: Long?,
    val actualUsdcAmount: Usdc6?,
    val actualFiatAmount: Usdc6?,
    val placedTxHash: TxHash?,
    val source: Source,
) {
    enum class Source { Subgraph, OnChain }

    // Pubkey deliberately not gated here — verifiedMerchantPubKey re-reads it on-chain before
    // encryption. Requiring it from subgraph would wedge polling whenever the indexer drops the column.
    val isAccepted: Boolean get() =
        status.onChain >= OrderStatus.ACCEPTED.onChain &&
            acceptedMerchantAddress != null
}
