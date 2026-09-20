// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.grouplink.model

import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.preference.api.PreferenceProvider
import co.electriccoin.zcash.preference.model.entry.PreferenceKey
import co.electriccoin.zcash.ui.common.provider.EncryptedJsonStore
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** Preferences in memory. A new store over the same instance is what the app sees after process death. */
internal class InMemoryPreferenceProvider : PreferenceProvider {
    private val values = mutableMapOf<String, MutableStateFlow<String?>>()

    private fun flowFor(key: PreferenceKey) = values.getOrPut(key.key) { MutableStateFlow(null) }

    fun raw(key: String): String? = values[key]?.value

    fun put(
        key: String,
        value: String,
    ) {
        flowFor(PreferenceKey(key)).value = value
    }

    override suspend fun hasKey(key: PreferenceKey): Boolean = flowFor(key).value != null

    override suspend fun putString(
        key: PreferenceKey,
        value: String?,
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
        value: Set<String>?,
    ) = error("Unused")

    override suspend fun putLong(
        key: PreferenceKey,
        value: Long?,
    ) = error("Unused")

    override suspend fun getLong(key: PreferenceKey): Long = error("Unused")

    override suspend fun getStringSet(key: PreferenceKey): Set<String> = error("Unused")

    override suspend fun clearPreferences(): Boolean = error("Unused")
}

internal fun pendingInviteStore(
    preferences: InMemoryPreferenceProvider,
    now: () -> Long = { TEST_NOW },
) = PendingGroupInviteStore(
    EncryptedJsonStore(
        mockk<EncryptedPreferenceProvider>().also { coEvery { it.invoke() } returns preferences },
        PendingGroupInviteStore.PREF_KEY,
        PendingGroupInvites.serializer(),
        strict = true,
    ),
    now,
)

internal const val TEST_NOW = 1_789_800_000_000L
