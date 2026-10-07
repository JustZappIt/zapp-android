// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

@file:UseSerializers(LowercaseAddressSerializer::class)

package xyz.justzappit.offramp.atomicswap

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.types.ChainId
import xyz.justzappit.offramp.p2p.Usdc6

/** The relayer's opt-in limits for locally proved Sepolia funding, whose gas it pays for [fee] in the escrow token. */
@Serializable
data class ReverseFundingTerms(
    val relayAdapt: Address,
    val token: Address,
    val maker: Address,
    val maxGasLimit: Long,
    val maxGasPriceWei: String,
    val maxCalldataBytes: Int,
    val fee: Usdc6? = null,
) {
    init {
        require(maxGasLimit > 0 && maxCalldataBytes in 1..MAX_FUNDING_CALLDATA_BYTES) { "invalid funding limits" }
        require(maxGasPriceWei.length in 1..MAX_UINT128_DECIMAL_DIGITS && maxGasPriceWei.all { it in '0'..'9' }) {
            "invalid sponsored gas price"
        }
        val price = BigInteger(maxGasPriceWei)
        require(price.signum() > 0 && price.bitLength() <= UINT128_BITS) { "invalid sponsored gas price" }
    }
}

/** Exact proved calldata, kept before submission. No spending key or phone account nonce crosses this boundary. */
@Serializable
data class ReverseFundingRequest(
    val swapId: SwapId,
    val chainId: ChainId,
    val to: Address,
    val data: String,
    val value: String,
) {
    init {
        require(value == "0") { "sponsored funding carries no ETH" }
        require(
            data.startsWith("0x") && data.length in MIN_CALLDATA_HEX_LENGTH..MAX_CALLDATA_HEX_LENGTH &&
                data.length % 2 == 0 && data.substring(2).all { it.digitToIntOrNull(HEX_RADIX) != null }
        ) { "invalid funding calldata" }
    }

    override fun toString() = "ReverseFundingRequest(swapId=$swapId, chainId=$chainId)"
}

/** Pins the advertised service and adapter before any proof is prepared. */
fun RelayerTerms.requireReverseFunding(
    deployment: SwapDeployment,
    relayAdapt: Address,
): ReverseFundingTerms {
    requireFundingService(deployment)
    // Terms without a fee predate paid sponsorship, whose funding the relayer would refuse.
    val funding = reverseFunding?.takeIf { it.fee != null }
    if (deployment.chainId != ChainId.ETHEREUM_SEPOLIA || funding == null) {
        throw AtomicSwapBlockedException(AtomicSwapBlock.FUNDING_UNAVAILABLE, "reverse funding is not sponsored")
    }
    if (funding.relayAdapt != relayAdapt || funding.token != deployment.token || funding.maker != deployment.maker) {
        throw AtomicSwapBlockedException(AtomicSwapBlock.WRONG_DEPLOYMENT, "funding terms name another deployment")
    }
    return funding
}

private fun RelayerTerms.requireFundingService(deployment: SwapDeployment) {
    val matches = chainId == deployment.chainId && contract == deployment.contract && relayer == deployment.relayer
    if (!matches || relayer == deployment.maker) {
        throw AtomicSwapBlockedException(AtomicSwapBlock.WRONG_DEPLOYMENT, "another deployment's funding relayer")
    }
}

private const val HEX_RADIX = 16
private const val MAX_FUNDING_CALLDATA_BYTES = 65_536
private const val MIN_CALLDATA_HEX_LENGTH = 10
private const val MAX_CALLDATA_HEX_LENGTH = 2 + MAX_FUNDING_CALLDATA_BYTES * 2
private const val MAX_UINT128_DECIMAL_DIGITS = 39
private const val UINT128_BITS = 128
