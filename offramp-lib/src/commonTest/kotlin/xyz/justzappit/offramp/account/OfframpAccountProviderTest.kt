// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.account

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import xyz.justzappit.evm.hd.EvmKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class OfframpAccountProviderTest {
    @Test
    fun `StaticOfframpAccountProvider zeros the seedphrase CharArray after derive`() =
        runTest {
            val mnemonic = MNEMONIC.toCharArray()
            val source = SingleArraySeedPhraseSource(mnemonic)
            val provider = StaticOfframpAccountProvider(source)

            provider.nextOfframpAccount()

            assertTrue(
                mnemonic.all { it == '\u0000' },
                "expected mnemonic chars wiped to \\u0000, got code points: " +
                    mnemonic.map { it.code }.toString(),
            )
        }

    @Test
    fun `StaticOfframpAccountProvider derives the expected canonical address`() =
        runTest {
            val source = SingleArraySeedPhraseSource(MNEMONIC.toCharArray())
            val provider = StaticOfframpAccountProvider(source)

            val key = provider.nextOfframpAccount()
            assertEquals(EXPECTED_CANONICAL_ADDRESS_HEX, key.address.lowercaseHex)
        }

    @Test
    fun `CachingOfframpAccountProvider invokes the wrapped provider exactly once across many calls`() =
        runTest {
            val counting = CountingOfframpAccountProvider()
            val caching = CachingOfframpAccountProvider(counting)

            val keys = (1..5).map { caching.nextOfframpAccount() }
            assertEquals(1, counting.callCount, "wrapped provider should be invoked exactly once")
            // All returned keys must be the same instance (cached).
            for (k in keys) assertSame(keys.first(), k)
        }

    @Test
    fun `CachingOfframpAccountProvider coalesces concurrent first-callers`() =
        runTest {
            val counting = CountingOfframpAccountProvider()
            val caching = CachingOfframpAccountProvider(counting)

            val deferred = (1..8).map { async { caching.nextOfframpAccount() } }
            val keys = deferred.awaitAll()

            assertEquals(1, counting.callCount)
            for (k in keys) assertSame(keys.first(), k)
        }

    private class SingleArraySeedPhraseSource(
        private val mnemonic: CharArray,
    ) : SeedPhraseSource {
        // Returns the same array every call so the test can inspect what the consumer did to it.
        override suspend fun getSeedPhrase(): CharArray = mnemonic
    }

    private class CountingOfframpAccountProvider : OfframpAccountProvider {
        var callCount = 0
            private set

        override suspend fun nextOfframpAccount(): EvmKey {
            callCount++
            return DevOfframpAccountProvider.key
        }
    }

    companion object {
        const val MNEMONIC =
            "abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon abandon abandon about"
        const val EXPECTED_CANONICAL_ADDRESS_HEX = "0x9858effd232b4033e47d90003d41ec34ecaeda94"
    }
}
