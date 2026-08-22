// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.gift.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PendingGiftLinkStoreTest {
    @Test
    fun `hands the link back for its token`() {
        val store = PendingGiftLinkStore()
        val token = store.accept(link("a"))

        assertEquals(link("a"), store.take(token))
    }

    @Test
    fun `never puts the link in the token`() {
        val store = PendingGiftLinkStore()
        val secret = "mnemonic-words-that-are-the-money"

        val token = store.accept(link(secret))

        assertEquals(false, token.contains(secret), "the token must not carry any of the link")
    }

    @Test
    fun `coalesces a repeated link`() {
        val store = PendingGiftLinkStore()
        store.put(link("a"))

        // Android re-delivers the same intent on recreation and from Recents. Opening a second
        // claim for one card would put two claim screens on the back stack for the same funds.
        assertEquals(
            GiftLinkIntake.AlreadyPending,
            store.put(link("a")),
            "the same link must not open twice while it is waiting"
        )
    }

    @Test
    fun `a taken link can be opened again`() {
        val store = PendingGiftLinkStore()
        val token = store.accept(link("a"))
        store.take(token)

        assertIs<GiftLinkIntake.Accepted>(
            store.put(link("a")),
            "backing out of a claim must not make the card unopenable"
        )
    }

    @Test
    fun `taking twice yields nothing the second time`() {
        val store = PendingGiftLinkStore()
        val token = store.accept(link("a"))
        store.take(token)

        assertNull(store.take(token))
    }

    @Test
    fun `a token from a dead process yields nothing`() {
        // The token survives in saved instance state; the store does not.
        assertNull(PendingGiftLinkStore().take("token-from-a-previous-process"))
    }

    @Test
    fun `distinct links get distinct tokens`() {
        val store = PendingGiftLinkStore()

        assertNotEquals(store.accept(link("a")), store.accept(link("b")))
    }

    @Test
    fun `bounds the store`() {
        val store = PendingGiftLinkStore()
        repeat(MAX_PENDING) { store.accept(link("card$it")) }

        assertEquals(
            GiftLinkIntake.Refused,
            store.put(link("one-too-many")),
            "the store must not grow without limit"
        )
    }

    @Test
    fun `a full store drains as claims are opened`() {
        val store = PendingGiftLinkStore()
        val tokens = List(MAX_PENDING) { store.accept(link("card$it")) }

        store.take(tokens.first())

        assertIs<GiftLinkIntake.Accepted>(store.put(link("one-more")), "taking a link must free its slot")
    }

    @Test
    fun `refuses a link over the size bound by character count`() {
        val oversized = link("a".repeat(GiftLinkCodec.MAX_URI_BYTES))

        assertEquals(GiftLinkIntake.Refused, PendingGiftLinkStore().put(oversized))
    }

    @Test
    fun `refuses a link whose bytes exceed the bound even though its length does not`() {
        // Four bytes per character, so a string comfortably under the character bound is well over
        // the byte bound. Checking only String.length would let this through.
        val fourByteChar = "😀"
        val raw = link(fourByteChar.repeat(GiftLinkCodec.MAX_URI_BYTES / 3))

        assertEquals(true, raw.length <= GiftLinkCodec.MAX_URI_BYTES, "precondition: under the character bound")
        assertEquals(true, raw.toByteArray().size > GiftLinkCodec.MAX_URI_BYTES, "precondition: over the byte bound")
        assertEquals(GiftLinkIntake.Refused, PendingGiftLinkStore().put(raw))
    }

    /**
     * A refusal here is a test that has already failed, so it says so rather than letting a later
     * assertion report something unrelated.
     */
    private fun PendingGiftLinkStore.accept(raw: String): String =
        assertNotNull(put(raw) as? GiftLinkIntake.Accepted, "expected the store to accept the link").token

    private fun link(body: String) = "https://$GIFT_LINK_HOST/c/v1#k=$body"

    private companion object {
        const val MAX_PENDING = 16
    }
}
