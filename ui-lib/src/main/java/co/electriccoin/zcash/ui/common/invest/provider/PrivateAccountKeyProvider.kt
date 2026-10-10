package co.electriccoin.zcash.ui.common.invest.provider

import xyz.justzappit.evm.hd.EvmKey
import xyz.justzappit.evm.intents.IntentsAccount
import xyz.justzappit.offramp.account.SeedPhraseSource

/**
 * The private-account key, derived from the wallet's recovery phrase for exactly as long as one signature
 * takes and wiped afterwards. Unlike the offramp key it is not cached: Invest signs a login about every
 * fifteen minutes at most, so the derivation cost is small next to keeping a second key in the heap.
 */
class PrivateAccountKeyProvider(
    private val seedPhraseSource: SeedPhraseSource,
) {
    @Volatile
    private var cachedAccountId: String? = null

    // Bumped by clear(), so a derivation that started before it can't cache the old account ID after.
    @Volatile
    private var generation = 0

    suspend fun <T> withKey(block: suspend (EvmKey) -> T): T {
        val startedIn = generation
        val mnemonic = seedPhraseSource.getSeedPhrase()
        val key =
            try {
                IntentsAccount.derive(mnemonic)
            } finally {
                mnemonic.fill('\u0000')
            }
        return try {
            if (startedIn == generation) cachedAccountId = IntentsAccount.accountId(key)
            block(key)
        } finally {
            key.zeroize()
        }
    }

    /** The Intents account ID (a lowercase 0x address). Public information, so it is kept once known. */
    suspend fun accountId(): String = cachedAccountId ?: withKey { IntentsAccount.accountId(it) }

    /** Forgets the account ID; the next wallet may have a different recovery phrase. */
    fun clear() {
        generation++
        cachedAccountId = null
    }
}
