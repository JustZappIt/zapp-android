// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.gift.model

import java.util.UUID

/** What the store made of an incoming link. */
sealed interface GiftLinkIntake {
    /** Held. [token] retrieves the link exactly once. */
    data class Accepted(
        val token: String,
    ) : GiftLinkIntake

    /** This exact link is already waiting, so the claim it would open is already on its way. */
    data object AlreadyPending : GiftLinkIntake

    /** Oversized, or too many already waiting. There is nothing to open. */
    data object Refused : GiftLinkIntake
}

/**
 * Holds an incoming gift URI in memory between the intent that delivered it and the claim screen
 * that consumes it.
 *
 * The link's fragment is the bearer secret, so it must not travel as a navigation argument: typed
 * routes are serialised into the back stack entry's arguments and survive into saved instance
 * state. Only the token travels, and it means nothing on its own.
 *
 * [take] leases the link until the claim screen calls [release], preventing overlapping attempts.
 */
class PendingGiftLinkStore {
    private val pending = LinkedHashMap<String, String>()
    private val active = mutableSetOf<String>()

    private var deferred: String? = null

    /** Registers [raw]. Never logs it, at any level. */
    @Synchronized
    fun put(raw: String): GiftLinkIntake =
        when {
            !isWithinGiftLinkSizeLimit(raw) -> GiftLinkIntake.Refused
            raw in pending.values || raw in active || raw == deferred -> GiftLinkIntake.AlreadyPending
            pending.size + active.size >= MAX_PENDING_URIS -> GiftLinkIntake.Refused
            else -> GiftLinkIntake.Accepted(UUID.randomUUID().toString().also { pending[it] = raw })
        }

    /** Returns what [token] stands for and leases it until [release]. */
    @Synchronized
    fun take(token: String): String? = pending.remove(token)?.also { active += it }

    @Synchronized
    fun release(raw: String) {
        active -= raw
    }

    /**
     * Holds [raw] across wallet creation, for a recipient who tapped a link before they had a
     * wallet to claim it into. Their claim screen is about to be left behind, and its token is
     * already spent, so this is the only thing standing between them and re-finding the message.
     */
    @Synchronized
    fun defer(raw: String) {
        active -= raw
        deferred = raw
    }

    /**
     * Registers the deferred link for a fresh claim and returns its token, or null if none is
     * waiting. Null also covers a link whose claim is already on its way in.
     */
    @Synchronized
    fun resumeDeferred(): String? =
        deferred?.let { raw ->
            deferred = null
            (put(raw) as? GiftLinkIntake.Accepted)?.token
        }

    private companion object {
        /** Bounds the store, so a flood of links cannot grow it without limit. */
        const val MAX_PENDING_URIS = 16
    }
}
