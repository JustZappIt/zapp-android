// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.config

import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.types.ChainId

data class P2pNetworkConfig(
    val name: String,
    val chainId: ChainId,
    val rpcUrl: String,
    val diamondAddress: Address,
    val usdcAddress: Address,
    val subgraphUrl: String,
    val baseExplorerUrl: String,
    val entryPointAddress: Address = Address.parse(P2pNetworks.ENTRYPOINT_V06),
    val accountFactoryAddress: Address = Address.parse(P2pNetworks.ACCOUNT_FACTORY_V06),
) {
    init {
        require(name.isNotBlank()) { "name must not be blank" }
        require(rpcUrl.isNotBlank()) { "rpcUrl must not be blank for '$name'" }
        require(subgraphUrl.isNotBlank()) { "subgraphUrl must not be blank for '$name'" }
        require(baseExplorerUrl.isNotBlank()) { "baseExplorerUrl must not be blank for '$name'" }
    }

    fun addressUrl(addressHex: String): String = baseExplorerUrl.trimEnd('/') + "/address/" + addressHex

    fun txUrl(txHash: String): String = baseExplorerUrl.trimEnd('/') + "/tx/" + txHash
}

object P2pNetworks {
    val SEPOLIA =
        P2pNetworkConfig(
            name = SEPOLIA_NAME,
            chainId = ChainId.BASE_SEPOLIA,
            rpcUrl = "https://sepolia.base.org",
            diamondAddress = Address.parse("0xeb0BB8E3c014D915D9B2df03aBB130a1Fb44beb9"),
            usdcAddress = Address.parse("0x4095fE4f1E636f11A95820BA2bB87F335Bd1040d"),
            subgraphUrl = "https://api.studio.thegraph.com/query/1745491/event-indexer/version/latest",
            baseExplorerUrl = "https://sepolia.basescan.org",
        )

    fun mainnet(rpcUrl: String, subgraphUrl: String): P2pNetworkConfig =
        P2pNetworkConfig(
            name = MAINNET_NAME,
            chainId = ChainId.BASE_MAINNET,
            rpcUrl = rpcUrl,
            diamondAddress = Address.parse(MAINNET_DIAMOND_ADDRESS),
            usdcAddress = Address.parse(MAINNET_USDC_ADDRESS),
            subgraphUrl = subgraphUrl,
            baseExplorerUrl = MAINNET_BASE_EXPLORER_URL,
        )

    const val SEPOLIA_NAME = "sepolia"
    const val MAINNET_NAME = "mainnet"
    val MAINNET_CHAIN_ID: ChainId = ChainId.BASE_MAINNET
    const val ENTRYPOINT_V06 = "0x5FF137D4b0FDCD49DcA30c7CF57E578a026d2789"
    const val ACCOUNT_FACTORY_V06 = "0x85e23b94e7F5E9cC1fF78BCe78cfb15B81f0DF00"
    const val MAINNET_DIAMOND_ADDRESS = "0x4cad6eC90e65baBec9335cAd728DDC610c316368"
    const val MAINNET_USDC_ADDRESS = "0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913"
    const val MAINNET_BASE_EXPLORER_URL = "https://basescan.org"
}
