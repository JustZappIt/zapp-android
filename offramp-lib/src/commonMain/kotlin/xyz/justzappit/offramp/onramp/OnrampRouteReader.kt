// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.onramp

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import xyz.justzappit.evm.math.bigIntegerZero
import xyz.justzappit.evm.rpc.BaseRpcClient
import xyz.justzappit.evm.types.Address
import xyz.justzappit.offramp.config.P2pNetworkConfig
import xyz.justzappit.offramp.liveness.LivenessCalls
import xyz.justzappit.offramp.p2p.CurrencyCode
import xyz.justzappit.offramp.p2p.Usdc6
import xyz.justzappit.offramp.reputation.ReputationCalls

/**
 * The one place that answers "what may this wallet buy per order": the Diamond's reputation-set
 * limit and, where an integrator is deployed, the selfie-set limit beside it. Read in one pass
 * because the two are decided against together, and one failed read fails the whole thing —
 * never a zeroed limit, which would show a verified wallet the same wall a cold one sees.
 */
class OnrampRouteReader(
    private val rpc: BaseRpcClient,
    private val network: P2pNetworkConfig,
) {
    suspend fun read(user: Address, currency: CurrencyCode): OnrampRouteLimits =
        coroutineScope {
            val direct =
                async {
                    ReputationCalls
                        .decodeUserTxLimits(
                            rpc.ethCall(network.diamondAddress, ReputationCalls.userTxLimitCalldata(user, currency)),
                        ).buy
                }
            // No integrator, no calls: the mainnet read costs exactly what it did before.
            val integrator = network.livenessIntegratorAddress
            val limit =
                integrator?.let { at ->
                    async { LivenessCalls.decodeUsdc6(rpc.ethCall(at, LivenessCalls.effectiveLimitCalldata(user))) }
                }
            val remaining =
                integrator?.let { at ->
                    async { LivenessCalls.decodeUint(rpc.ethCall(at, LivenessCalls.remainingDailyCountCalldata(user))) }
                }
            // `effectiveLimit` does not know the switch: a paused integrator still quotes a limit
            // it will refuse to honour, and the refusal belongs on the amount screen.
            val paused =
                integrator?.let { at ->
                    async { LivenessCalls.decodeBool(rpc.ethCall(at, LivenessCalls.pausedCalldata())) }
                }
            OnrampRouteLimits(
                direct = direct.await(),
                integrator = limit?.await() ?: Usdc6.ZERO,
                integratorOrdersRemaining = remaining?.await() ?: bigIntegerZero,
                integratorPaused = paused?.await() ?: false,
            )
        }
}
