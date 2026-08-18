// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.provider

import android.content.Context

/**
 * Plaintext mirror of the blocked messaging public keys. The encrypted address book is
 * authoritative, but it is seed-derived and cannot load in the cold, headless ChatWakeService —
 * this mirror keeps the synchronous blocked filter available there. Must be cleared whenever the
 * wallet (and with it the address book) is deleted, or the previous wallet's blocklist leaks into
 * the next one.
 */
interface ChatBlockedKeysStorageProvider {
    fun get(): Set<String>

    fun store(keys: Set<String>)

    fun clear()
}

class ChatBlockedKeysStorageProviderImpl(
    private val context: Context
) : ChatBlockedKeysStorageProvider {
    private val prefs by lazy {
        // The pre-unification moderation store is dead code since ChatModerationRepository was
        // removed; delete its plaintext keys+names rather than leaving them on disk forever.
        context.deleteSharedPreferences(LEGACY_MODERATION_PREFS)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    override fun get(): Set<String> = prefs.getStringSet(KEY_BLOCKED, emptySet())?.toSet().orEmpty()

    override fun store(keys: Set<String>) {
        prefs.edit().putStringSet(KEY_BLOCKED, keys).apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val PREFS_NAME = "chat_blocked_keys"
        const val KEY_BLOCKED = "blocked_public_keys"
        const val LEGACY_MODERATION_PREFS = "chat_moderation"
    }
}
