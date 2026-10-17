// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.atomicswap

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import xyz.justzappit.offramp.atomicswap.AtomicSwapBlock
import xyz.justzappit.offramp.atomicswap.AtomicSwapBlockedException
import xyz.justzappit.offramp.atomicswap.SwapTokenState
import xyz.justzappit.offramp.atomicswap.SwapTokenStore
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AtomicSwapTokensTest {
    @Test
    fun `a quote is turned away while the pinned issuer's refusal of this device is kept`() =
        runTest {
            val issuer = checkNotNull(AtomicSwapTestnet.deployment.swap.tokenIssuer)
            val store = Store(SwapTokenState(refusedBy = issuer.name))
            val deployments = mockk<AtomicSwapDeployments> { every { current } returns AtomicSwapTestnet.deployment }
            val tokens = AtomicSwapTokens(deployments, mockk(), store)

            val refused = assertFailsWith<AtomicSwapBlockedException> { tokens.requireNotRefused() }
            assertEquals(AtomicSwapBlock.TOKENS_DEVICE_REFUSED, refused.reason)

            store.state = SwapTokenState(refusedBy = "another issuer")
            tokens.requireNotRefused()
            store.state = SwapTokenState()
            tokens.requireNotRefused()
        }

    private class Store(
        var state: SwapTokenState,
    ) : SwapTokenStore {
        override suspend fun load() = state

        override suspend fun save(state: SwapTokenState) {
            this.state = state
        }
    }
}
