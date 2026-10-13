// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.reputation

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import xyz.justzappit.evm.rpc.BaseRpcClient
import xyz.justzappit.evm.rpc.RpcException
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
    private val blockPollMillis: Long = BLOCK_POLL_MILLIS,
    private val blockPollAttempts: Int = BLOCK_POLL_ATTEMPTS,
) {
    suspend fun read(user: Address, currency: CurrencyCode): ReputationSummary = read(user, currency, LATEST)

    /**
     * The summary as of [blockNumber], the block a verification's receipt named. The receipt comes
     * from the bundler's node and a `latest` read can land on one a block behind it, which reports
     * the verification as not yet made. A node that has not reached the block refuses the call
     * instead, and the call is retried until one has.
     */
    suspend fun readAt(user: Address, currency: CurrencyCode, blockNumber: String): ReputationSummary {
        var attempts = 0
        while (true) {
            try {
                return read(user, currency, blockNumber)
            } catch (e: RpcException) {
                if (++attempts >= blockPollAttempts) throw e
            }
            delay(blockPollMillis)
        }
    }

    private suspend fun read(user: Address, currency: CurrencyCode, blockTag: String): ReputationSummary =
        coroutineScope {
            val rm = network.reputationManagerAddress
            val diamond = network.diamondAddress

            suspend fun call(to: Address, data: ByteArray): ByteArray = rpc.ethCall(to, data, blockTag)

            val rmUser = async { ReputationCalls.decodeRmUser(call(rm, ReputationCalls.rmusersCalldata(user))) }
            val verified =
                async {
                    ReputationCalls.decodeSocialVerified(
                        call(rm, ReputationCalls.socialVerifiedCalldata(user)),
                    )
                }
            val awards =
                SocialPlatform.entries.map { platform ->
                    async {
                        platform to
                            ReputationCalls.decodeUint(
                                call(rm, ReputationCalls.rpAwardCalldata(platform)),
                            )
                    }
                }
            val identityVerified =
                IdentityCheck.entries.map { check ->
                    async {
                        check.takeIf {
                            ReputationCalls.decodeBool(
                                call(rm, ReputationCalls.identityVerifiedCalldata(check, user)),
                            )
                        }
                    }
                }
            val identityAwards =
                IdentityCheck.entries.map { check ->
                    async {
                        check to
                            ReputationCalls.decodeUint(
                                call(rm, ReputationCalls.identityRpAwardCalldata(check)),
                            )
                    }
                }
            val limits =
                async {
                    ReputationCalls.decodeUserTxLimits(
                        call(diamond, ReputationCalls.userTxLimitCalldata(user, currency)),
                    )
                }
            val maxBuy =
                async {
                    ReputationCalls.decodeUsdc6(
                        call(diamond, ReputationCalls.maxBuyTxLimitCalldata(currency)),
                    )
                }
            val rpPerUsdc =
                async {
                    ReputationCalls.decodeRpPerUsdcLimit(
                        call(diamond, ReputationCalls.rpPerUsdcLimitCalldata(currency)),
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
                maxBuyLimit = maxBuy.await(),
                rpPerUsdc = rpPerUsdc.await(),
                identityVerified = identityVerified.awaitAll().filterNotNull().toSet(),
                identityAwards = identityAwards.awaitAll().toMap(),
            )
        }

    private companion object {
        const val LATEST = "latest"

        /** Base seals a block every two seconds; a node a block behind catches up well inside this. */
        const val BLOCK_POLL_MILLIS = 1_000L
        const val BLOCK_POLL_ATTEMPTS = 10
    }
}
