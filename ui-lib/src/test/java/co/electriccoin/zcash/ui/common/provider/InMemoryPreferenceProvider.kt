// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.provider

import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.preference.api.PreferenceProvider
import co.electriccoin.zcash.preference.model.entry.PreferenceKey
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** String preferences kept in memory, for the stores built on them. */
internal class InMemoryPreferenceProvider : PreferenceProvider {
    private val values = mutableMapOf<String, MutableStateFlow<String?>>()

    private fun flowFor(key: PreferenceKey) = values.getOrPut(key.key) { MutableStateFlow(null) }

    override suspend fun hasKey(key: PreferenceKey): Boolean = flowFor(key).value != null

    override suspend fun putString(
        key: PreferenceKey,
        value: String?
    ) {
        flowFor(key).value = value
    }

    override suspend fun getString(key: PreferenceKey): String? = flowFor(key).value

    override fun observe(key: PreferenceKey): Flow<String?> = flowFor(key)

    override suspend fun remove(key: PreferenceKey) {
        flowFor(key).value = null
    }

    override suspend fun putStringSet(
        key: PreferenceKey,
        value: Set<String>?
    ) = error("Unused")

    override suspend fun putLong(
        key: PreferenceKey,
        value: Long?
    ) = error("Unused")

    override suspend fun getLong(key: PreferenceKey): Long = error("Unused")

    override suspend fun getStringSet(key: PreferenceKey): Set<String> = error("Unused")

    override suspend fun clearPreferences(): Boolean = error("Unused")

    fun encrypted(): EncryptedPreferenceProvider =
        mockk<EncryptedPreferenceProvider>().also { coEvery { it.invoke() } returns this }
}
