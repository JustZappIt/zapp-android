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

/** One tapped invite, held until the person joins, says not now, or it lapses. */
@Serializable
data class PendingGroupInvite(
    /** Opaque, and the only thing that travels in navigation. */
    val token: String,
    /** The canonical link. A bearer secret. */
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
    /** Held. [token] opens the preview for it. A repeat of a held link gets the same token. */
    data class Accepted(
        val token: String,
    ) : GroupInviteIntake

    /** Not a link this build can hold. There is nothing to open, only something to explain. */
    data object Refused : GroupInviteIntake
}

/**
 * Holds tapped invites between the tap and the preview's answer, across process death.
 *
 * A link can arrive before there is anyone to join as: no wallet yet, onboarding half done, or the
 * chat identity still being made. So unlike the gift store this one is persisted, in encrypted
 * preferences, which are cleared with the wallet. Only the `Received` state lives here. Once the
 * person asks to join, the SDK holds the request and this entry is removed with the secret in it.
 */
class PendingGroupInviteStore internal constructor(
    private val store: EncryptedJsonStore<PendingGroupInvites>,
    private val now: () -> Long,
) {
    constructor(encryptedPreferenceProvider: EncryptedPreferenceProvider) : this(
        EncryptedJsonStore(encryptedPreferenceProvider, PREF_KEY, PendingGroupInvites.serializer(), strict = true),
        System::currentTimeMillis,
    )

    private val mutex = Mutex()

    // This store is the only writer of its key, so counting its own writes is enough to observe it,
    // and unlike observing the preference it survives a record that fails to decode.
    private val changes = MutableStateFlow(0)

    /** Registers [raw]. Never logs it, at any level. */
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

    /** The link [token] stands for, while it is still held. */
    suspend fun link(token: String): String? = mutex.withLock { live().firstOrNull { it.token == token }?.link }

    /** The newest held invite, for the preview to open once the app is ready for it. */
    suspend fun newest(): String? = mutex.withLock { live().firstOrNull()?.token }

    suspend fun remove(token: String) {
        mutex.withLock {
            val current = live()
            if (current.any { it.token == token }) write(current.filterNot { it.token == token })
        }
    }

    suspend fun clear() {
        mutex.withLock { write(emptyList()) }
    }

    /** Tokens held right now, newest first. Emits again whenever a link arrives or leaves. */
    fun observeTokens(): Flow<List<String>> =
        changes
            .map { mutex.withLock { live().map { it.token } } }
            .distinctUntilChanged()

    /** What is held and not yet lapsed. Drops a record it cannot fully read rather than act on it. */
    private suspend fun live(): List<PendingGroupInvite> {
        val stored =
            try {
                store.get()
            } catch (_: StoreCorruptedException) {
                // Written by a build that knows more than this one, or damaged. Either way the
                // person can tap their link again; guessing at it is worse.
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
