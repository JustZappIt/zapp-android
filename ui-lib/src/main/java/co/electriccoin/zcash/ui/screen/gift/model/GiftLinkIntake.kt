// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.gift.model

/**
 * The bounds an incoming gift URI has to clear before anything acts on it.
 *
 * Every rule here exists because of a real failure mode, not as defence in depth for its own sake:
 * Android re-delivers intents on recreation and from Recents, a deeplink is an untrusted input of
 * arbitrary size, and the same link arriving twice must open one claim rather than two. Ported from
 * Vizor's `MainActivity.captureIncomingUri`.
 *
 * Kept out of the Activity so all of it is unit-testable on the JVM, and stateful because
 * coalescing duplicates is the whole point. Not thread-safe; call it from the main thread, which is
 * where intents are delivered.
 */
class GiftLinkIntake {
    private val pending = LinkedHashSet<String>()

    /**
     * Whether [raw] is a URI worth opening a claim for.
     *
     * Returns false for anything oversized, already queued, or arriving once the queue is full.
     * Never logs [raw] — the fragment is the bearer secret.
     */
    fun accept(raw: String): Boolean {
        val acceptable =
            // Both size bounds, in this order: length counts UTF-16 units and cannot bound the
            // byte size, while measuring bytes first would mean copying whatever we were handed.
            raw.length <= GiftLinkCodec.MAX_URI_BYTES &&
                raw.toByteArray().size <= GiftLinkCodec.MAX_URI_BYTES &&
                raw !in pending &&
                pending.size < MAX_PENDING_URIS
        if (acceptable) pending += raw
        return acceptable
    }

    /**
     * Forgets [raw], so a card the recipient backed out of can be opened again.
     *
     * Without this the coalescing above would make a cancelled claim un-retryable for the lifetime
     * of the process.
     */
    fun release(raw: String) {
        pending -= raw
    }

    private companion object {
        /** Bounds the queue, so a flood of links cannot grow it without limit. */
        const val MAX_PENDING_URIS = 16
    }
}
