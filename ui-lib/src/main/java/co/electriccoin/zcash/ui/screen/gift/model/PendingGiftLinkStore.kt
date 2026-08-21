// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.gift.model

import java.util.UUID

/**
 * Holds an incoming gift URI in memory between the intent that delivered it and the claim screen
 * that consumes it.
 *
 * The link's fragment is the bearer secret, so it must not travel as a navigation argument: typed
 * routes are serialised into the back stack entry's arguments and survive into saved instance
 * state. Only the token travels, and it means nothing on its own.
 *
 * [take] removes the entry, which is also what lets a recipient who backed out of a claim open the
 * same link again. Ported from Vizor's payment link intake, which drains its queue for the same
 * reason. Not thread-safe; call it from the main thread, where intents arrive and view models are
 * constructed.
 */
class PendingGiftLinkStore {
    private val pending = LinkedHashMap<String, String>()

    /**
     * Registers [raw] and returns the token that retrieves it, or null when nothing should be
     * opened for it — oversized, already waiting, or arriving once the store is full.
     *
     * Never logs [raw].
     */
    fun put(raw: String): String? {
        // Both size bounds, in this order: length counts UTF-16 units and cannot bound the byte
        // size, while measuring bytes first would mean copying whatever we were handed.
        val acceptable =
            raw.length <= GiftLinkCodec.MAX_URI_BYTES &&
                raw.toByteArray().size <= GiftLinkCodec.MAX_URI_BYTES &&
                raw !in pending.values &&
                pending.size < MAX_PENDING_URIS
        if (!acceptable) return null
        return UUID.randomUUID().toString().also { pending[it] = raw }
    }

    /** Returns what [token] stands for and forgets it, or null once it has already been taken. */
    fun take(token: String): String? = pending.remove(token)

    private companion object {
        /** Bounds the store, so a flood of links cannot grow it without limit. */
        const val MAX_PENDING_URIS = 16
    }
}
