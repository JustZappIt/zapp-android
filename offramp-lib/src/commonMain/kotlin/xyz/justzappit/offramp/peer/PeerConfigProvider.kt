// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.peer

/**
 * Resolves Peer's network from the p2p.me network the build is already pinned to. Null means the
 * rails are unavailable on this build, which is the whole answer on sepolia: Peer only exists on
 * Base mainnet, so an absent rail is honest where a half-working one is not.
 */
class PeerConfigProvider(
    private val p2pNetworkName: String,
) {
    fun currentOrNull(): PeerNetworkConfig? = PeerNetworks.forP2pNetworkOrNull(p2pNetworkName)

    val isAvailable: Boolean get() = currentOrNull() != null
}
