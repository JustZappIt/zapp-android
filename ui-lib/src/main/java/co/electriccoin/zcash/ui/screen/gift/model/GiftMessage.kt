// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.gift.model

import java.text.BreakIterator

/**
 * Limits on the optional note a sender attaches to a card.
 *
 * Both bounds are part of the link format shared with iOS, and both are enforced as the sender
 * types as well as on decode.
 */
object GiftMessage {
    /** Longest message, in grapheme clusters — what a reader would call "characters". */
    const val MAX_GRAPHEMES = 128

    /**
     * Longest message in UTF-8 bytes. A separate bound because clusters say nothing about size: 128
     * emoji are well under the cluster limit and well over this one.
     */
    const val MAX_UTF8_BYTES = 512

    /**
     * Counts grapheme clusters. `String.length` counts UTF-16 code units, which makes one emoji 2
     * and a family emoji 7 or more — using it would reject messages that look far shorter than the
     * limit, and it is what a "128 characters left" counter would show the sender.
     */
    fun graphemeCount(value: String): Int {
        val iterator = BreakIterator.getCharacterInstance()
        iterator.setText(value)
        var count = 0
        while (iterator.next() != BreakIterator.DONE) count++
        return count
    }

    fun isWithinLimits(value: String): Boolean =
        graphemeCount(value) <= MAX_GRAPHEMES && value.toByteArray().size <= MAX_UTF8_BYTES
}
