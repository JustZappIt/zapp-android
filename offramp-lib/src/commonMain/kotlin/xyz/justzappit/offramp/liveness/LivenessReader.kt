// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.liveness

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import xyz.justzappit.evm.rpc.BaseRpcClient
import xyz.justzappit.evm.rpc.RpcException
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
    private val blockPollMillis: Long = BLOCK_POLL_MILLIS,
    private val blockPollAttempts: Int = BLOCK_POLL_ATTEMPTS,
) {
    val isAvailable: Boolean get() = network.livenessIntegratorAddress != null

    /** Null on a network with no integrator. One failed read fails the whole thing, never a zeroed standing. */
    suspend fun read(user: Address): LivenessStanding? = read(user, LATEST)

    /**
     * The standing as of [blockNumber], the block an attestation's receipt named.
     *
     * The receipt comes from the bundler's node and this read goes to another, which can trail
     * it by a block. A `latest` read from behind the attestation reports the wallet unverified
     * with a $0 limit — and that is what the "you can now buy up to" line would say. Pinning the
     * read to the receipt's block makes a node that has not reached it refuse the call instead,
     * and the call is retried until one has.
     */
    suspend fun readAt(user: Address, blockNumber: String): LivenessStanding? {
        var attempts = 0
        while (true) {
            try {
                return read(user, blockNumber)
            } catch (e: RpcException) {
                if (++attempts >= blockPollAttempts) throw e
            }
            delay(blockPollMillis)
        }
    }

    private suspend fun read(user: Address, blockTag: String): LivenessStanding? {
        val integrator = network.livenessIntegratorAddress ?: return null
        return coroutineScope {
            val verified =
                async {
                    LivenessCalls.decodeBool(rpc.ethCall(integrator, LivenessCalls.verifiedCalldata(user), blockTag))
                }
            val limit =
                async {
                    LivenessCalls.decodeUsdc6(
                        rpc.ethCall(integrator, LivenessCalls.effectiveLimitCalldata(user), blockTag),
                    )
                }
            val cap =
                async {
                    LivenessCalls.decodeUsdc6(rpc.ethCall(integrator, LivenessCalls.tierCapCalldata(), blockTag))
                }
            LivenessStanding(isVerified = verified.await(), limit = limit.await(), tierCap = cap.await())
        }
    }

    private companion object {
        const val LATEST = "latest"

        /** Base seals a block every two seconds; a node a block behind is caught up well inside this. */
        const val BLOCK_POLL_MILLIS = 1_000L
        const val BLOCK_POLL_ATTEMPTS = 10
    }
}
