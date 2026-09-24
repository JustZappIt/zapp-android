// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.grouplink.model

import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.ui.common.provider.EncryptedJsonStore
import co.electriccoin.zcash.ui.common.provider.StoreCorruptedException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class PendingGroupInvite(
    val token: String,
    val link: String,
    val receivedAt: Long,
) {
    override fun toString(): String = "PendingGroupInvite(token=$token, link=<redacted>, receivedAt=$receivedAt)"
}

@Serializable
data class PendingGroupInvites(
    val version: Int = VERSION,
    /** Newest first. */
    val invites: List<PendingGroupInvite> = emptyList(),
) {
    init {
        require(version == VERSION) { "Unknown pending invite version" }
    }

    companion object {
        const val VERSION = 1
    }
}

sealed interface GroupInviteIntake {
    /** A repeat of a held link gets the same token. */
    data class Accepted(
        val token: String,
    ) : GroupInviteIntake

    data object Refused : GroupInviteIntake
}

/** Persisted, unlike the gift store: a link can arrive before there is anyone to join as. */
class PendingGroupInviteStore internal constructor(
    private val store: EncryptedJsonStore<PendingGroupInvites>,
    private val now: () -> Long,
) {
    constructor(encryptedPreferenceProvider: EncryptedPreferenceProvider) : this(
        EncryptedJsonStore(encryptedPreferenceProvider, PREF_KEY, PendingGroupInvites.serializer(), strict = true),
        System::currentTimeMillis,
    )

    private val mutex = Mutex()

    // The only writer of its key, so counting writes observes it, even past a record that fails to decode.
    private val changes = MutableStateFlow(0)

    suspend fun put(raw: String): GroupInviteIntake {
        val link = GroupInviteLinks.canonical(raw) ?: return GroupInviteIntake.Refused
        return mutex.withLock {
            val current = live()
            val token = current.firstOrNull { it.link == link }?.token ?: UUID.randomUUID().toString()
            val entry = PendingGroupInvite(token = token, link = link, receivedAt = now())
            write(listOf(entry) + current.filterNot { it.token == token })
            GroupInviteIntake.Accepted(token)
        }
    }

    suspend fun link(token: String): String? = mutex.withLock { live().firstOrNull { it.token == token }?.link }

    suspend fun remove(token: String) {
        mutex.withLock {
            val current = live()
            if (current.any { it.token == token }) write(current.filterNot { it.token == token })
        }
    }

    fun observeTokens(): Flow<List<String>> =
        changes
            .map { mutex.withLock { live().map { it.token } } }
            .distinctUntilChanged()

    private suspend fun live(): List<PendingGroupInvite> {
        val stored =
            try {
                store.get()
            } catch (_: StoreCorruptedException) {
                // Written by a newer build, or damaged. The person can tap their link again.
                write(emptyList())
                null
            }
        val invites = stored?.invites.orEmpty()
        val kept = invites.filter(::isLive)
        if (kept.size != invites.size) write(kept)
        return kept
    }

    private fun isLive(invite: PendingGroupInvite): Boolean {
        // A little slack for a clock set back, not enough to keep a link forever.
        val age = now() - invite.receivedAt
        return age in -CLOCK_SLACK_MS..TTL_MS
    }

    private suspend fun write(invites: List<PendingGroupInvite>) {
        if (invites.isEmpty()) {
            store.clear()
        } else {
            store.set(PendingGroupInvites(invites = invites.take(MAX_INVITES)))
        }
        changes.update { it + 1 }
    }

    companion object {
        internal const val PREF_KEY = "group_invite_pending_v1"
        const val MAX_INVITES = 4
        const val TTL_MS = 7L * 24 * 60 * 60 * 1000
        private const val CLOCK_SLACK_MS = 24L * 60 * 60 * 1000
    }
}
