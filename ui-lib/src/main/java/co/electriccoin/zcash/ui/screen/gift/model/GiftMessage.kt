// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.gift.model

import java.text.BreakIterator

/**
 * Limits on the optional note a sender attaches to a card. Both bounds are part of the link format
 * shared with iOS, and both are enforced as the sender types as well as on decode.
 */
object GiftMessage {
    /** Longest message, in grapheme clusters — what a reader would call "characters". */
    const val MAX_GRAPHEMES = 128

    /** Separate bound, because clusters say nothing about size: 128 emoji clear one and not the other. */
    const val MAX_UTF8_BYTES = 512

    /**
     * `String.length` counts UTF-16 code units, making one emoji 2 and a family emoji 7 or more, so
     * it would both reject short-looking messages and show the sender a nonsense counter.
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
