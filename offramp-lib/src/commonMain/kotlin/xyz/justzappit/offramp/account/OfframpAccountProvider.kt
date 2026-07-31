// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.account

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.justzappit.evm.hd.EvmKey
import xyz.justzappit.evm.hd.EvmKeyDerivation

interface OfframpAccountProvider {
    suspend fun nextOfframpAccount(): EvmKey
}

class StaticOfframpAccountProvider(
    private val seedPhraseSource: SeedPhraseSource,
    private val fixedAccountIndex: Int = 0,
) : OfframpAccountProvider {
    override suspend fun nextOfframpAccount(): EvmKey {
        val mnemonic = seedPhraseSource.getSeedPhrase()
        return try {
            EvmKeyDerivation.derive(mnemonic = mnemonic, accountIndex = fixedAccountIndex)
        } finally {
            mnemonic.fill('\u0000')
        }
    }
}

/**
 * Caches the derived [EvmKey] so the wrapped provider runs at most once per process — the
 * mnemonic crosses the [SeedPhraseSource] seam once per app lifetime, not once per order.
 * Concurrent first-callers coalesce on a [Mutex].
 */
class CachingOfframpAccountProvider(
    private val delegate: OfframpAccountProvider,
) : OfframpAccountProvider {
    private var cached: EvmKey? = null
    private val mutex = Mutex()

    override suspend fun nextOfframpAccount(): EvmKey {
        return mutex.withLock {
            cached ?: delegate.nextOfframpAccount().also { cached = it }
        }
    }
}
