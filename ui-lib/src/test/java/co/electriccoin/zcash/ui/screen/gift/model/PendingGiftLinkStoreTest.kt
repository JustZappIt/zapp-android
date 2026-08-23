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
    fun `an active link cannot be opened again until released`() {
        val store = PendingGiftLinkStore()
        val token = store.accept(link("a"))
        val raw = assertNotNull(store.take(token))

        assertEquals(
            GiftLinkIntake.AlreadyPending,
            store.put(link("a")),
        )
        store.release(raw)
        assertIs<GiftLinkIntake.Accepted>(store.put(link("a")))
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
        store.release(link("card0"))

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

    @Test
    fun `hands a deferred link back once a wallet exists`() {
        val store = PendingGiftLinkStore()
        // The claim screen opened with no wallet behind it, spending the token on the way in.
        store.take(store.accept(link("a")))

        store.defer(link("a"))
        val resumed = assertNotNull(store.resumeDeferred(), "a card left to go and make a wallet must come back")

        assertEquals(link("a"), store.take(resumed))
    }

    @Test
    fun `resumes a deferred link only once`() {
        val store = PendingGiftLinkStore()
        store.defer(link("a"))
        store.resumeDeferred()

        // Onboarding reports READY once, but the effect that reads this can run again on
        // recomposition; a second claim screen would be two attempts to spend the same note.
        assertNull(store.resumeDeferred())
    }

    @Test
    fun `has nothing to resume when no card was deferred`() {
        assertNull(PendingGiftLinkStore().resumeDeferred())
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
