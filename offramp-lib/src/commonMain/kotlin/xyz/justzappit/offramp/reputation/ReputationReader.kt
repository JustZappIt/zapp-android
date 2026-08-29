// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.reputation

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import xyz.justzappit.evm.rpc.BaseRpcClient
import xyz.justzappit.evm.types.Address
import xyz.justzappit.offramp.config.P2pNetworkConfig
import xyz.justzappit.offramp.p2p.CurrencyCode

/**
 * Reads everything the reputation screen shows, in one pass across both contracts.
 *
 * The reads fan out concurrently because they are independent and the screen has no partial state
 * worth rendering: a limit without the ceiling it approaches, or verifications without the points
 * they earned, is a screen that has to be corrected a moment later. One failure fails the whole
 * read, and the caller renders "couldn't reach Base" — **never** a zeroed summary, which would
 * show an already-verified user the same wall a cold wallet sees.
 */
class ReputationReader(
    private val rpc: BaseRpcClient,
    private val network: P2pNetworkConfig,
) {
    suspend fun read(user: Address, currency: CurrencyCode): ReputationSummary =
        coroutineScope {
            val rm = network.reputationManagerAddress
            val diamond = network.diamondAddress

            val rmUser = async { ReputationCalls.decodeRmUser(rpc.ethCall(rm, ReputationCalls.rmusersCalldata(user))) }
            val verified =
                async {
                    ReputationCalls.decodeSocialVerified(
                        rpc.ethCall(rm, ReputationCalls.socialVerifiedCalldata(user)),
                    )
                }
            val awards =
                SocialPlatform.entries.map { platform ->
                    async {
                        platform to
                            ReputationCalls.decodeUint(
                                rpc.ethCall(rm, ReputationCalls.rpAwardCalldata(platform)),
                            )
                    }
                }
            val limits =
                async {
                    ReputationCalls.decodeUserTxLimits(
                        rpc.ethCall(diamond, ReputationCalls.userTxLimitCalldata(user, currency)),
                    )
                }
            val maxBuy =
                async {
                    ReputationCalls.decodeUsdc6(
                        rpc.ethCall(diamond, ReputationCalls.maxBuyTxLimitCalldata(currency)),
                    )
                }
            val rpPerUsdc =
                async {
                    ReputationCalls.decodeRpPerUsdcLimit(
                        rpc.ethCall(diamond, ReputationCalls.rpPerUsdcLimitCalldata(currency)),
                    )
                }

            val user0 = rmUser.await()
            val txLimits = limits.await()
            ReputationSummary(
                currency = currency,
                points = user0.reputationPoints,
                isBlacklisted = user0.isBlacklisted,
                verified = verified.await(),
                awards = awards.awaitAll().toMap(),
                buyLimit = txLimits.buy,
                sellLimit = txLimits.sell,
                maxBuyLimit = maxBuy.await(),
                rpPerUsdc = rpPerUsdc.await(),
            )
        }
}
