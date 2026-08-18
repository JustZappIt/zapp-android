// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.config

class P2pConfigProvider(
    private val networkName: String,
    private val rpcUrlOverride: String? = null,
    private val subgraphUrlOverride: String? = null,
) {
    fun current(): P2pNetworkConfig =
        when (networkName.lowercase()) {
            P2pNetworks.MAINNET_NAME -> {
                P2pNetworks.mainnet(
                    rpcUrl =
                        rpcUrlOverride
                            ?: error("P2P_RPC_URL_BASE_MAINNET must be set when P2P_NETWORK=mainnet"),
                    subgraphUrl =
                        subgraphUrlOverride
                            ?: error("P2P_SUBGRAPH_URL_MAINNET must be set when P2P_NETWORK=mainnet"),
                )
            }

            P2pNetworks.SEPOLIA_NAME -> {
                P2pNetworks.SEPOLIA.copy(
                    rpcUrl = rpcUrlOverride ?: P2pNetworks.SEPOLIA.rpcUrl,
                    subgraphUrl = subgraphUrlOverride ?: P2pNetworks.SEPOLIA.subgraphUrl,
                )
            }

            else -> {
                error("Unknown P2P_NETWORK: $networkName (expected 'sepolia' or 'mainnet')")
            }
        }
}
