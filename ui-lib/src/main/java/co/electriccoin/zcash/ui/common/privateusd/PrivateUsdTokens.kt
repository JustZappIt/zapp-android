// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.privateusd

import androidx.annotation.StringRes
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapDeployment
import io.ktor.http.Url
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.types.ChainId
import xyz.justzappit.railgun.RailgunEndpoints
import xyz.justzappit.railgun.RailgunNetwork

data class PrivateUsdToken(
    val address: Address,
    val symbol: String,
    @param:StringRes val name: Int,
    val decimals: Int,
    /** Counts toward the private USD balance. */
    val isDollar: Boolean,
)

/** Ethereum Sepolia, where testnet builds keep private USD. */
object Sepolia {
    val CHAIN_ID = ChainId.ETHEREUM_SEPOLIA
    val RPC_URL = Url(RailgunEndpoints.SEPOLIA_RPC_URL)
    const val EXPLORER_TX_URL = "https://sepolia.etherscan.io/tx/"
    val RAILGUN_PROXY = Address.parse("0xeCFCf3b4eC647c4Ca6D49108b311b7a7C9543fea")

    // The testnet maker's token has no decimals() or symbol(); it stands in for USDC.
    val TEST_USD = Address.parse("0x5764D0044bef5AA839E0dDafE2073421101B9Ed8")

    // Circle's test USDC, the one Railgun accepts.
    val USDC = Address.parse("0x1c7D4B196Cb0C7B01d743Fbc6116a902379C7238")

    // What shielding ETH wraps it into.
    val WETH = Address.parse("0xfFf9976782d46CC05630D1f6eBAb18b2324d6B14")
}

/** The tokens the app knows how to show. Decimals come from here, never from the chain. */
object PrivateUsdTokens {
    private const val USD_DECIMALS = 6
    private const val ETH_DECIMALS = 18

    private val sepolia =
        listOf(
            PrivateUsdToken(Sepolia.TEST_USD, "tUSD", R.string.private_usd_token_test_usd, USD_DECIMALS, true),
            PrivateUsdToken(Sepolia.USDC, "USDC", R.string.private_usd_token_usdc, USD_DECIMALS, true),
            PrivateUsdToken(Sepolia.WETH, "WETH", R.string.private_usd_token_weth, ETH_DECIMALS, false),
        )
    private val ethereum =
        listOf(
            PrivateUsdToken(
                Address.parse("0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48"),
                "USDC",
                R.string.private_usd_token_usdc,
                USD_DECIMALS,
                true,
            ),
        )

    fun of(network: RailgunNetwork): List<PrivateUsdToken> =
        when (network) {
            RailgunNetwork.SEPOLIA -> sepolia
            RailgunNetwork.MAINNET -> ethereum
        }

    fun find(
        network: RailgunNetwork,
        address: Address
    ): PrivateUsdToken? = of(network).firstOrNull { it.address == address }
}

/** The dollar token this deployment's conversions settle in. */
val AtomicSwapDeployment.privateUsdToken: PrivateUsdToken
    get() =
        checkNotNull(PrivateUsdTokens.find(railgunNetwork, swap.token)?.takeIf { it.isDollar }) {
            "conversions settle in a token that isn't a known dollar"
        }
