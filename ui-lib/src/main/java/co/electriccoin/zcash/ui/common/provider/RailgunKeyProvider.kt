// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.provider

import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.preference.model.entry.PreferenceKey
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.justzappit.evm.util.hexToBytes
import xyz.justzappit.evm.util.toHex
import java.security.SecureRandom

/** Random keys for the Railgun wallet, kept only in the encrypted preferences and wiped with the wallet. */
interface RailgunKeyProvider {
    /** Encrypts the wallet in the WebView's storage; losing it costs a rescan, never funds. */
    suspend fun encryptionKey(): ByteArray

    /** The testnet gas account's private key. Losing it loses only the test ETH on it. */
    suspend fun gasAccountKey(): ByteArray

    /**
     * Runs [forget] once, then drops the key of the wallet the Zcash seed itself opened before the Railgun wallet
     * had a mnemonic of its own. A [forget] that throws runs again next time.
     */
    suspend fun retireZcashSeedWallet(forget: suspend () -> Unit)
}

class RailgunKeyProviderImpl(
    private val encryptedPreferenceProvider: EncryptedPreferenceProvider,
) : RailgunKeyProvider {
    private val lock = Mutex()

    override suspend fun encryptionKey() = getOrCreate(ENCRYPTION_KEY)

    override suspend fun gasAccountKey() = getOrCreate(GAS_ACCOUNT_KEY)

    override suspend fun retireZcashSeedWallet(forget: suspend () -> Unit) =
        lock.withLock {
            val preferences = encryptedPreferenceProvider()
            if (!preferences.hasKey(ZCASH_SEED_WALLET_RETIRED)) {
                forget()
                preferences.remove(RETIRED_ENCRYPTION_KEY)
                preferences.putString(ZCASH_SEED_WALLET_RETIRED, true.toString())
            }
        }

    private suspend fun getOrCreate(key: PreferenceKey): ByteArray =
        lock.withLock {
            val preferences = encryptedPreferenceProvider()
            preferences.getString(key)?.hexToBytes()
                ?: ByteArray(KEY_BYTES).also {
                    SecureRandom().nextBytes(it)
                    preferences.putString(key, it.toHex())
                }
        }

    private companion object {
        // It encrypted a wallet of the Zcash seed itself, since deleted with the page's old origin.
        val RETIRED_ENCRYPTION_KEY = PreferenceKey("railgun_wallet_encryption_key_v1")
        val ENCRYPTION_KEY = PreferenceKey("railgun_wallet_encryption_key_v2")
        val GAS_ACCOUNT_KEY = PreferenceKey("railgun_testnet_gas_account_key_v1")
        val ZCASH_SEED_WALLET_RETIRED = PreferenceKey("railgun_zcash_seed_wallet_retired")
        const val KEY_BYTES = 32
    }
}
