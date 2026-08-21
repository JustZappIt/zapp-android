// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.gift.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GiftLinkIntakeTest {
    @Test
    fun `accepts an ordinary link once`() {
        assertTrue(GiftLinkIntake().accept(link("a")))
    }

    @Test
    fun `coalesces a repeated link`() {
        val intake = GiftLinkIntake()

        assertTrue(intake.accept(link("a")))
        // Android re-delivers the same intent on recreation and from Recents. Opening a second
        // claim for one card would put two claim screens on the back stack for the same funds.
        assertFalse(intake.accept(link("a")), "the same link must not enqueue twice")
    }

    @Test
    fun `a released link can be opened again`() {
        val intake = GiftLinkIntake()
        intake.accept(link("a"))

        intake.release(link("a"))

        assertTrue(intake.accept(link("a")), "backing out of a claim must not make the card unopenable")
    }

    @Test
    fun `bounds the queue`() {
        val intake = GiftLinkIntake()
        repeat(MAX_PENDING) { assertTrue(intake.accept(link("card$it")), "link $it should fit") }

        assertFalse(intake.accept(link("one-too-many")), "the queue must not grow without limit")
    }

    @Test
    fun `rejects a link over the size bound by character count`() {
        val oversized = link("a".repeat(GiftLinkCodec.MAX_URI_BYTES))

        assertFalse(GiftLinkIntake().accept(oversized))
    }

    @Test
    fun `rejects a link whose bytes exceed the bound even though its length does not`() {
        // Four bytes per character, so a string comfortably under the character bound is well over
        // the byte bound. Checking only String.length would let this through.
        val fourByteChar = "😀"
        val raw = link(fourByteChar.repeat(GiftLinkCodec.MAX_URI_BYTES / 3))

        assertTrue(raw.length <= GiftLinkCodec.MAX_URI_BYTES, "precondition: under the character bound")
        assertTrue(raw.toByteArray().size > GiftLinkCodec.MAX_URI_BYTES, "precondition: over the byte bound")
        assertFalse(GiftLinkIntake().accept(raw))
    }

    private fun link(body: String) = "https://$GIFT_LINK_HOST/c/v1#k=$body"

    private companion object {
        const val MAX_PENDING = 16
    }
}
