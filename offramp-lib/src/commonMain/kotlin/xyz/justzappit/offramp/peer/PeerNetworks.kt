// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.peer

import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.types.ChainId
import xyz.justzappit.offramp.config.P2pNetworks

/**
 * Peer's Base deployment. Kept separate from `P2pNetworkConfig` rather than widening it: that
 * config's fields are p2p.me's diamond and subgraph, and a rail that has neither would carry two
 * dead columns.
 *
 * Staging is a second deployment on Base mainnet, not a testnet, so it still costs real gas.
 */
data class PeerNetworkConfig(
    val name: String,
    val chainId: ChainId,
    val escrowAddress: Address,
    val gatingServiceAddress: Address,
    val oracleAdapterAddress: Address,
    val usdcAddress: Address,
    val curatorUrl: String,
    val indexerUrl: String,
    val baseExplorerUrl: String,
    /** Peer's own explorer, which reads an order the way a buyer sees it. Production only. */
    val orderExplorerUrl: String? = null,
) {
    init {
        require(name.isNotBlank()) { "name must not be blank" }
        require(curatorUrl.isNotBlank()) { "curatorUrl must not be blank for '$name'" }
        require(indexerUrl.isNotBlank()) { "indexerUrl must not be blank for '$name'" }
    }

    fun txUrl(txHash: String): String = baseExplorerUrl.trimEnd('/') + "/tx/" + txHash

    fun addressUrl(addressHex: String): String = baseExplorerUrl.trimEnd('/') + "/address/" + addressHex

    fun orderUrl(depositId: PeerDepositId): String? =
        orderExplorerUrl?.let { it.trimEnd('/') + "/" + depositId.composite }
}

object PeerNetworks {
    const val PRODUCTION_NAME = "production"
    const val STAGING_NAME = "staging"

    val PRODUCTION =
        PeerNetworkConfig(
            name = PRODUCTION_NAME,
            chainId = ChainId.BASE_MAINNET,
            escrowAddress = Address.parse("0x777777779d229cdF3110e9de47943791c26300Ef"),
            gatingServiceAddress = Address.parse("0x396D31055Db28C0C6f36e8b36f18FE7227248a97"),
            // The SDK constant, confirmed against the oracleRateConfig of live EscrowV2 deposits.
            // The `contracts-v2` addresses JSON lists a different adapter that nothing on chain uses.
            oracleAdapterAddress = Address.parse("0xfc81d1b5841e697973af3072fc8e03af76cb39ef"),
            usdcAddress = Address.parse(P2pNetworks.MAINNET_USDC_ADDRESS),
            curatorUrl = "https://api.zkp2p.xyz",
            indexerUrl = "https://indexer.zkp2p.xyz/v1/graphql",
            baseExplorerUrl = P2pNetworks.MAINNET_BASE_EXPLORER_URL,
            orderExplorerUrl = "https://peerlytics.xyz/explorer/deposit",
        )

    val STAGING =
        PRODUCTION.copy(
            name = STAGING_NAME,
            escrowAddress = Address.parse("0x77e8f808FE201075e0bD651CD46fdF239fc83265"),
            curatorUrl = "https://api-staging.zkp2p.xyz",
            indexerUrl = "https://indexer-staging.zkp2p.xyz/v1/graphql",
            // The explorer only indexes the production escrow, so a staging order 404s there.
            orderExplorerUrl = null,
        )

    /**
     * Peer only exists on Base mainnet, so the rails are absent entirely on a sepolia build rather
     * than half-present and failing at the first call.
     */
    fun forP2pNetworkOrNull(p2pNetworkName: String): PeerNetworkConfig? =
        when (p2pNetworkName.lowercase()) {
            P2pNetworks.MAINNET_NAME -> PRODUCTION
            else -> null
        }

    /** Practical floor: below this the deposit is dust that can never fill. */
    const val MIN_CASHOUT_MICROS: Long = 10_000L

    /**
     * Peer's orderbook hides any deposit with less than this still available. It filters on what is
     * left rather than what was posted, so an order that sells down past this goes dark mid-life
     * while staying valid and withdrawable. Verified inclusive: a deposit at exactly 5 is listed.
     */
    const val ORDERBOOK_MIN_VISIBLE_MICROS: Long = 5_000_000L

    /**
     * The UI gates here, at the point an order is visible to buyers at all. It also has to stay
     * above [INTENT_AMOUNT_FLOOR_MICROS], or `intentAmountMin == intentAmountMax` and only a request
     * for the exact amount can match.
     */
    const val RECOMMENDED_MIN_CASHOUT_MICROS: Long = ORDERBOOK_MIN_VISIBLE_MICROS

    /** Buyers take any slice down to this, so small buyers can nibble a large order. */
    const val INTENT_AMOUNT_FLOOR_MICROS: Long = 1_000_000L

    const val ORACLE_MAX_STALENESS_SECONDS: Long = 86_400L
}
