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

/**
 * Random per-install keys for the Railgun wallet, kept only in the encrypted preferences, whose
 * master key lives in the Android keystore.
 */
interface RailgunKeyProvider {
    /**
     * What Railgun's SDK encrypts its wallet with, the mnemonic included, in the WebView's storage.
     * Losing it costs a rescan, not funds: the wallet is re-created from the seed.
     */
    suspend fun encryptionKey(): ByteArray

    /** The testnet gas account's private key. Losing it loses only the test ETH on it. */
    suspend fun gasAccountKey(): ByteArray
}

class RailgunKeyProviderImpl(
    private val encryptedPreferenceProvider: EncryptedPreferenceProvider,
) : RailgunKeyProvider {
    private val lock = Mutex()

    override suspend fun encryptionKey() = getOrCreate(ENCRYPTION_KEY)

    override suspend fun gasAccountKey() = getOrCreate(GAS_ACCOUNT_KEY)

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
        val ENCRYPTION_KEY = PreferenceKey("railgun_wallet_encryption_key_v1")
        val GAS_ACCOUNT_KEY = PreferenceKey("railgun_testnet_gas_account_key_v1")
        const val KEY_BYTES = 32
    }
}
