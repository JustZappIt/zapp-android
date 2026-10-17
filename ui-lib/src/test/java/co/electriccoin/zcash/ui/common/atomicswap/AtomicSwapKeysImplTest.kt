// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.atomicswap

import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.common.usecase.GetWalletSeedBytesUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AtomicSwapKeysImplTest {
    private val seeds = mockk<GetWalletSeedBytesUseCase>()

    @Test
    fun `every key is derived on the pinned deployment's Zcash network`() =
        runTest {
            val seed = ByteArray(64) { 1 }
            coEvery { seeds() } answers { seed.copyOf() }

            assertEquals(false to 3, keys(AtomicSwapTestnet.deployment).withKey(3) { it.mainnet to it.index })
            assertEquals(true to 3, keys(AtomicSwapMainnet.deployment).withKey(3) { it.mainnet to it.index })
        }

    @Test
    fun `a build that pins no deployment derives no keys and never reads the seed`() =
        runTest {
            val failure = assertFailsWith<IllegalStateException> { keys(pinned = null).withKey(0) { it.mainnet } }

            assertTrue("no swap deployment" in failure.message.orEmpty())
            coVerify(exactly = 0) { seeds() }
        }

    private fun keys(pinned: AtomicSwapDeployment?): AtomicSwapKeysImpl {
        val deployments = mockk<AtomicSwapDeployments> { every { current } returns pinned }
        return AtomicSwapKeysImpl(seeds, mockk<PersistableWalletProvider>(), deployments)
    }
}
