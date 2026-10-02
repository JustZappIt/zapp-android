// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.provider

import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import xyz.justzappit.offramp.identity.IdentityVerificationStore
import xyz.justzappit.offramp.identity.PendingIdentityVerification

/** Cleared with the wallet's encrypted preferences. Each wallet/network/check has its own record. */
class IdentityVerificationStorageProvider(
    private val encryptedPreferenceProvider: EncryptedPreferenceProvider,
) : IdentityVerificationStore {
    override suspend fun get(key: String): PendingIdentityVerification? = store(key).get()

    override suspend fun set(key: String, pending: PendingIdentityVerification?) {
        if (pending == null) store(key).clear() else store(key).set(pending)
    }

    private fun store(key: String) =
        EncryptedJsonStore(
            encryptedPreferenceProvider,
            "identity_verification_v1_$key",
            PendingIdentityVerification.serializer(),
            strict = true,
        )
}
