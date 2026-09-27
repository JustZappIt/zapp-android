// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.privateusd

import xyz.justzappit.railgun.RailgunNetwork

data class PrivateUsdToken(
    val address: String,
    val symbol: String,
    val name: String,
    val decimals: Int,
    /** Counts toward the private USD balance. */
    val isDollar: Boolean,
)

/** The tokens the app knows how to show. Decimals come from here, never from the chain. */
object PrivateUsdTokens {
    private const val USD_DECIMALS = 6
    private const val ETH_DECIMALS = 18

    // The testnet maker's token has no decimals() or symbol(); it stands in for USDC.
    private val sepolia =
        listOf(
            PrivateUsdToken("0x5764D0044bef5AA839E0dDafE2073421101B9Ed8", "tUSD", "Test dollar", USD_DECIMALS, true),
            PrivateUsdToken("0x1c7D4B196Cb0C7B01d743Fbc6116a902379C7238", "USDC", "USD Coin", USD_DECIMALS, true),
            PrivateUsdToken("0xfFf9976782d46CC05630D1f6eBAb18b2324d6B14", "WETH", "Wrapped Ether", ETH_DECIMALS, false),
        )
    private val ethereum =
        listOf(
            PrivateUsdToken("0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48", "USDC", "USD Coin", USD_DECIMALS, true),
        )

    fun of(network: RailgunNetwork): List<PrivateUsdToken> =
        when (network) {
            RailgunNetwork.SEPOLIA -> sepolia
            RailgunNetwork.MAINNET -> ethereum
        }

    fun find(
        network: RailgunNetwork,
        address: String
    ): PrivateUsdToken? = of(network).firstOrNull { it.address.equals(address, ignoreCase = true) }
}
