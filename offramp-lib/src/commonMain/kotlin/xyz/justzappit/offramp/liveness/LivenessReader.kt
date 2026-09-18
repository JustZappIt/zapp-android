// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.liveness

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import xyz.justzappit.evm.rpc.BaseRpcClient
import xyz.justzappit.evm.types.Address
import xyz.justzappit.offramp.config.P2pNetworkConfig
import xyz.justzappit.offramp.p2p.Usdc6

/** One wallet's standing on the liveness integrator, as the chain reports it. */
data class LivenessStanding(
    val isVerified: Boolean,
    /** What this wallet may buy per order through the integrator; 0 until verified. */
    val limit: Usdc6,
    /** What verifying is worth to a wallet that has not yet. */
    val tierCap: Usdc6,
)

class LivenessReader(
    private val rpc: BaseRpcClient,
    private val network: P2pNetworkConfig,
) {
    val isAvailable: Boolean get() = network.livenessIntegratorAddress != null

    /** Null on a network with no integrator. One failed read fails the whole thing, never a zeroed standing. */
    suspend fun read(user: Address): LivenessStanding? {
        val integrator = network.livenessIntegratorAddress ?: return null
        return coroutineScope {
            val verified =
                async { LivenessCalls.decodeBool(rpc.ethCall(integrator, LivenessCalls.verifiedCalldata(user))) }
            val limit =
                async { LivenessCalls.decodeUsdc6(rpc.ethCall(integrator, LivenessCalls.effectiveLimitCalldata(user))) }
            val cap = async { LivenessCalls.decodeUsdc6(rpc.ethCall(integrator, LivenessCalls.tierCapCalldata())) }
            LivenessStanding(isVerified = verified.await(), limit = limit.await(), tierCap = cap.await())
        }
    }
}
