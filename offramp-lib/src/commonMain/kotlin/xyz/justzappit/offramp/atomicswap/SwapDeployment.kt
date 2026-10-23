// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

@file:UseSerializers(LowercaseAddressSerializer::class)

package xyz.justzappit.offramp.atomicswap

import io.ktor.http.Url
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.types.ChainId

/** A ZecSwap deployment, kept with each reverse swap's record; what's at its default isn't written. */
@Serializable
data class SwapDeployment(
    val makerUrl: Url,
    val relayerUrl: Url,
    val rpcUrl: Url,
    val chainId: ChainId,
    val contract: Address,
    val token: Address,
    @SerialName("railgun")
    val railgunProxy: Address,
    /** The only maker whose quotes are taken. */
    val maker: Address,
    /** The only relayer payouts are signed for. */
    val relayer: Address,
    /** Confirmations, including its own block, required for opens, escrows and payouts in either direction. */
    @SerialName("confirmations")
    val escrowConfirmations: Long = DEFAULT_ESCROW_CONFIRMATIONS,
    val zcashNetwork: SwapZcashNetwork = SwapZcashNetwork.TESTNET,
    /** Confirmations the maker waits for before it counts a deposit; a sweep home waits as long before it's done. */
    val zcashConfirmations: Int = DEFAULT_ZCASH_CONFIRMATIONS,
    /** Where the maker's accepts get their Privacy Pass tokens; none pinned refuses a maker that asks for one. */
    val tokenIssuer: SwapTokenIssuer? = null,
    /**
     * The windows [contract] must answer before any swap of it is read; none for a lock contract, whose swaps this
     * build keeps as history and never drives. Records kept before the windows contract have none.
     */
    val windows: SwapWindows? = null,
)

/** A lock contract users swapped on before the windows contract: its swaps show as history, and nothing drives them. */
val SwapDeployment.retired: Boolean get() = windows == null

/** Railgun's own contracts on a chain, as its `NETWORK_CONFIG` names them (@railgun-community/shared-models 8.2.0). */
data class RailgunContracts(
    val proxy: Address,
    val relayAdapt: Address,
) {
    companion object {
        val ETHEREUM =
            RailgunContracts(
                proxy = Address.parse("0xFA7093CDD9EE6932B4eb2c9e1cde7CE00B1FA4b9"),
                relayAdapt = Address.parse("0xAc9f360Ae85469B27aEDdEaFC579Ef2d052aD405"),
            )
        val SEPOLIA =
            RailgunContracts(
                proxy = Address.parse("0xeCFCf3b4eC647c4Ca6D49108b311b7a7C9543fea"),
                relayAdapt = Address.parse("0x7e3d929EbD5bDC84d02Bd3205c777578f33A214D"),
            )
    }
}

/** Circle's USDC on Ethereum: what mainnet swaps pay out, the one token zecswap-api's `Network` takes there. */
val ETHEREUM_USDC: Address = Address.parse("0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48")

/**
 * Railgun's contracts on the deployment's chain, where its Zcash network settles on Railgun's own deployment as
 * zecswap-api's `Network` fixes it: Ethereum for mainnet, Sepolia for testnet. None on any other chain.
 */
val SwapDeployment.railgun: RailgunContracts?
    get() =
        when (zcashNetwork) {
            SwapZcashNetwork.MAINNET -> RailgunContracts.ETHEREUM.takeIf { chainId == ChainId.ETHEREUM_MAINNET }
            SwapZcashNetwork.TESTNET -> RailgunContracts.SEPOLIA.takeIf { chainId == ChainId.ETHEREUM_SEPOLIA }
        }

/** How a forward swap's maker times its deposit. */
data class ZcashDepositTerms(
    /**
     * The soonest `t0` a deposit may start with: time for it to get its confirmations before the maker gives up on a
     * deposit not confirmed in full, which it does a while before `t0`.
     */
    val minSecondsToT0: Long = MIN_SECONDS_TO_T0,
    /**
     * The longest after the accept a deposit may start: the maker cancels a swap with nothing mined into its deposit
     * account soon after the open, and a deposit that lands after that only comes back, less two Zcash fees.
     */
    val depositWindowSeconds: Long = DEPOSIT_WINDOW_SECONDS,
)

private const val DEPOSIT_WINDOW_SECONDS = 10 * 60L
private const val DEFAULT_ESCROW_CONFIRMATIONS = 3L
private const val DEFAULT_ZCASH_CONFIRMATIONS = 3
