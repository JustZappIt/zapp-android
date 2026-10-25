// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.atomicswap

import io.ktor.client.HttpClient
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import xyz.justzappit.offramp.atomicswap.DeviceAttestation
import xyz.justzappit.offramp.atomicswap.SwapTokenState
import xyz.justzappit.offramp.atomicswap.SwapTokenStore
import xyz.justzappit.offramp.atomicswap.SwapTokens
import kotlin.io.encoding.Base64
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class AtomicSwapTokensTest {
    @Test
    fun `nothing is fetched ahead for a device the pinned issuer refused`() =
        runTest {
            val issuer = checkNotNull(AtomicSwapTestnet.deployment.swap.tokenIssuer)
            val deployments = mockk<AtomicSwapDeployments> { every { current } returns AtomicSwapTestnet.deployment }
            val store = Store(SwapTokenState(challenge = makerChallenge(issuer.name), refusedBy = issuer.name))
            var routes = 0
            val route: suspend () -> HttpClient = {
                routes++
                error("no issuer in this test")
            }
            val unattested = DeviceAttestation { _, _ -> error("no attestation in this test") }
            val tokens =
                AtomicSwapTokens(deployments, store, this) { pinned ->
                    SwapTokens(pinned, route, mockk(), unattested, store) { NOW_SECONDS }
                }

            tokens.prefetch()
            advanceUntilIdle()
            assertEquals(0, routes, "the issuer isn't asked again ahead of a maker's ask")

            store.state = store.state.copy(refusedBy = "another issuer")
            tokens.prefetch()
            advanceUntilIdle()
            assertEquals(1, routes, "a device it hasn't refused fetches ahead")
        }

    private class Store(
        var state: SwapTokenState,
    ) : SwapTokenStore {
        override suspend fun load() = state

        override suspend fun save(state: SwapTokenState) {
            this.state = state
        }
    }

    private companion object {
        const val NOW_SECONDS = 1_790_000_000L
        const val TODAY = NOW_SECONDS / 86_400

        /** RFC 9577's challenge, dated as zecSwap's makers date theirs: today in the last 8 bytes of its context. */
        fun makerChallenge(issuer: String): String {
            val name = issuer.encodeToByteArray()
            val day = ByteArray(8) { (TODAY ushr 8 * (7 - it)).toByte() }
            val origin = byteArrayOf(0, 5) + "maker".encodeToByteArray()
            val context = byteArrayOf(32) + ByteArray(24) + day
            return Base64.UrlSafe
                .withPadding(Base64.PaddingOption.ABSENT)
                .encode(byteArrayOf(0, 2, 0, name.size.toByte()) + name + context + origin)
        }
    }
}
