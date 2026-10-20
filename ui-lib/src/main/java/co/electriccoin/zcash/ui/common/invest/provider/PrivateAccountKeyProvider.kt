package co.electriccoin.zcash.ui.common.invest.provider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.justzappit.evm.hd.EvmKey
import xyz.justzappit.evm.intents.IntentsAccount
import xyz.justzappit.offramp.account.SeedPhraseSource

/** The private-account key, derived from the recovery phrase for one use and wiped after it. */
class PrivateAccountKeyProvider(
    private val seedPhraseSource: SeedPhraseSource,
) {
    @Volatile
    private var cachedAccountId: String? = null

    @Volatile
    private var generation = 0

    suspend fun <T> withKey(block: suspend (EvmKey) -> T): T =
        withContext(Dispatchers.Default) {
            val startedIn = generation
            val mnemonic = seedPhraseSource.getSeedPhrase()
            val key =
                try {
                    IntentsAccount.derive(mnemonic)
                } finally {
                    mnemonic.fill('\u0000')
                }
            try {
                if (startedIn == generation) cachedAccountId = IntentsAccount.accountId(key)
                block(key)
            } finally {
                key.zeroize()
            }
        }

    suspend fun accountId(): String = cachedAccountId ?: withKey { IntentsAccount.accountId(it) }

    fun clear() {
        generation++
        cachedAccountId = null
    }
}
