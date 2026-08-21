// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.gift.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GiftCardLedgerTest {
    @Test
    fun `adds a draft card`() {
        val cards = GiftCardLedger.add(emptyList(), card())

        assertEquals(listOf(card()), cards)
    }

    @Test
    fun `refuses to add a card whose id is already taken`() {
        val cards = GiftCardLedger.add(emptyList(), card())

        // Silently replacing would drop the earlier card's mnemonic, and with it its funds.
        assertFailsWith<GiftCardTransitionException> {
            GiftCardLedger.add(cards, card(amount = 999))
        }
    }

    @Test
    fun `refuses to add a card that claims to be past draft`() {
        assertFailsWith<GiftCardTransitionException> {
            GiftCardLedger.add(emptyList(), card(status = GiftCardStatus.FUNDED, txid = TXID))
        }
        assertFailsWith<GiftCardTransitionException> {
            GiftCardLedger.add(emptyList(), card(txid = TXID))
        }
    }

    @Test
    fun `records a submitted funding txid without claiming the card has mined`() {
        val cards = GiftCardLedger.recordFundingSubmitted(listOf(card()), ID, TXID, LATER)

        assertEquals(TXID, cards.single().fundingTxid)
        assertEquals(GiftCardStatus.DRAFT, cards.single().status)
        assertEquals(LATER, cards.single().updatedAt)
    }

    @Test
    fun `re-recording the same funding txid is not an error`() {
        val once = GiftCardLedger.recordFundingSubmitted(listOf(card()), ID, TXID, LATER)

        val twice = GiftCardLedger.recordFundingSubmitted(once, ID, TXID, LATER)

        assertEquals(TXID, twice.single().fundingTxid)
    }

    @Test
    fun `refuses a second funding transaction for one card`() {
        val cards = GiftCardLedger.recordFundingSubmitted(listOf(card()), ID, TXID, LATER)

        assertFailsWith<GiftCardTransitionException> {
            GiftCardLedger.recordFundingSubmitted(cards, ID, OTHER_TXID, LATER)
        }
    }

    @Test
    fun `marks a card funded`() {
        val cards = GiftCardLedger.markFunded(listOf(card()), ID, TXID, LATER)

        assertEquals(GiftCardStatus.FUNDED, cards.single().status)
        assertEquals(TXID, cards.single().fundingTxid)
        assertEquals(LATER, cards.single().updatedAt)
    }

    @Test
    fun `refuses to mark a card funded without a txid`() {
        // The guard exists so a card can never read as funded with no transaction behind it.
        assertFailsWith<GiftCardTransitionException> { GiftCardLedger.markFunded(listOf(card()), ID, "", LATER) }
        assertFailsWith<GiftCardTransitionException> { GiftCardLedger.markFunded(listOf(card()), ID, "  ", LATER) }
    }

    @Test
    fun `never regresses a card's status`() {
        val submitted = GiftCardLedger.recordFundingSubmitted(listOf(card()), ID, TXID, LATER)
        val shared = GiftCardLedger.markShared(GiftCardLedger.markFunded(submitted, ID, TXID, LATER), ID, LATER)

        // Recording the submitted txid must not undo FUNDED, and a mining confirmation that lands
        // after the sender has already shared must not walk the card back out of SHARED. Both are
        // orderings the real flow produces.
        assertEquals(GiftCardStatus.FUNDED, GiftCardLedger.markFunded(submitted, ID, TXID, LATER).single().status)
        assertEquals(
            GiftCardStatus.FUNDED,
            GiftCardLedger
                .recordFundingSubmitted(GiftCardLedger.markFunded(submitted, ID, TXID, LATER), ID, TXID, LATEST)
                .single()
                .status
        )
        assertEquals(GiftCardStatus.SHARED, GiftCardLedger.markFunded(shared, ID, TXID, LATEST).single().status)
        assertEquals(GiftCardStatus.SHARED, GiftCardLedger.archive(shared, ID, LATEST).single().status)
    }

    @Test
    fun `refuses to mark a shared card funded again under a different transaction`() {
        val shared = GiftCardLedger.markShared(GiftCardLedger.markFunded(listOf(card()), ID, TXID, LATER), ID, LATER)

        assertFailsWith<GiftCardTransitionException> { GiftCardLedger.markFunded(shared, ID, OTHER_TXID, LATER) }
    }

    @Test
    fun `shares a card once its funding has been submitted but not yet mined`() {
        val submitted = GiftCardLedger.recordFundingSubmitted(listOf(card()), ID, TXID, LATER)

        val shared = GiftCardLedger.markShared(submitted, ID, LATER)

        assertEquals(GiftCardStatus.SHARED, shared.single().status)
    }

    @Test
    fun `refuses to share a card that was never funded`() {
        // Otherwise the sender hands out a link to an address holding nothing.
        assertFailsWith<GiftCardTransitionException> { GiftCardLedger.markShared(listOf(card()), ID, LATER) }
    }

    @Test
    fun `sharing twice is not an error`() {
        val once = GiftCardLedger.markShared(GiftCardLedger.markFunded(listOf(card()), ID, TXID, LATER), ID, LATER)

        val twice = GiftCardLedger.markShared(once, ID, LATER)

        assertEquals(GiftCardStatus.SHARED, twice.single().status)
    }

    @Test
    fun `archiving keeps the record and its key material`() {
        val funded = GiftCardLedger.markFunded(listOf(card()), ID, TXID, LATER)

        val archived = GiftCardLedger.archive(funded, ID, LATER)

        assertEquals(LATER, archived.single().archivedAt)
        assertEquals(MNEMONIC, archived.single().mnemonic)
        assertEquals(GiftCardStatus.FUNDED, archived.single().status)
    }

    @Test
    fun `archiving twice keeps the first timestamp`() {
        val funded = GiftCardLedger.markFunded(listOf(card()), ID, TXID, LATER)

        val archived = GiftCardLedger.archive(GiftCardLedger.archive(funded, ID, LATER), ID, LATEST)

        assertEquals(LATER, archived.single().archivedAt)
    }

    @Test
    fun `rejects a mutation naming a card that is not there`() {
        assertFailsWith<GiftCardTransitionException> { GiftCardLedger.markFunded(listOf(card()), "nope", TXID, LATER) }
        assertFailsWith<GiftCardTransitionException> { GiftCardLedger.markShared(listOf(card()), "nope", LATER) }
        assertFailsWith<GiftCardTransitionException> { GiftCardLedger.archive(listOf(card()), "nope", LATER) }
        assertFailsWith<GiftCardTransitionException> {
            GiftCardLedger.recordFundingSubmitted(listOf(card()), "nope", TXID, LATER)
        }
    }

    @Test
    fun `leaves every other card untouched`() {
        val others = listOf(card(id = "a"), card(id = "b"), card(id = "c"))

        val mutated = GiftCardLedger.markFunded(others, "b", TXID, LATER)

        assertEquals(3, mutated.size)
        assertEquals(others[0], mutated[0])
        assertEquals(others[2], mutated[2])
        assertNull(mutated[0].fundingTxid)
    }

    @Test
    fun `reports funded cards whose links were never shared`() {
        val draft = card(id = "draft")
        val submitted = GiftCardLedger.recordFundingSubmitted(listOf(card(id = "submitted")), "submitted", TXID, LATER)
        val funded = GiftCardLedger.markFunded(listOf(card(id = "funded")), "funded", TXID, LATER)
        val shared = GiftCardLedger.markShared(funded, "funded", LATER)

        assertFalse(GiftCardLedger.hasUnsharedFunds(listOf(draft), ACCOUNT))
        assertTrue(GiftCardLedger.hasUnsharedFunds(submitted, ACCOUNT))
        assertTrue(GiftCardLedger.hasUnsharedFunds(funded, ACCOUNT))
        assertFalse(GiftCardLedger.hasUnsharedFunds(shared, ACCOUNT))
    }

    @Test
    fun `counts archived cards as unshared funds`() {
        val funded = GiftCardLedger.markFunded(listOf(card()), ID, TXID, LATER)

        val archived = GiftCardLedger.archive(funded, ID, LATER)

        // Archiving hides a card. It does not move its money, and there is no reclaim.
        assertTrue(GiftCardLedger.hasUnsharedFunds(archived, ACCOUNT))
    }

    @Test
    fun `scopes unshared funds to the owning account`() {
        val funded = GiftCardLedger.markFunded(listOf(card()), ID, TXID, LATER)

        assertTrue(GiftCardLedger.hasUnsharedFunds(funded, ACCOUNT))
        assertFalse(GiftCardLedger.hasUnsharedFunds(funded, "some-other-account"))
    }

    @Test
    fun `sees unshared funds in any account when asked wallet-wide`() {
        val funded = GiftCardLedger.markFunded(listOf(card()), ID, TXID, LATER)

        // What a wallet wipe has to ask: it clears every account's cards at once.
        assertTrue(GiftCardLedger.hasUnsharedFunds(funded))
        assertFalse(GiftCardLedger.hasUnsharedFunds(GiftCardLedger.markShared(funded, ID, LATER)))
        assertFalse(GiftCardLedger.hasUnsharedFunds(listOf(card())))
        assertFalse(GiftCardLedger.hasUnsharedFunds(emptyList()))
    }

    @Test
    fun `counts a broadcast whose outcome was never seen as unshared funds`() {
        val attempted = GiftCardLedger.setFundingAttemptedAt(listOf(card()), ID, LATER)

        // No txid was ever written, but the money may already have left. Guessing "unfunded" here
        // is what would let the wallet be wiped out from under it.
        assertNull(attempted.single().fundingTxid)
        assertTrue(GiftCardLedger.hasUnsharedFunds(attempted))
        assertTrue(GiftCardLedger.hasUnsharedFunds(attempted, ACCOUNT))
    }

    @Test
    fun `clears the attempt once its outcome is known`() {
        val attempted = GiftCardLedger.setFundingAttemptedAt(listOf(card()), ID, LATER)

        // A txid is a stronger record of the same fact, so recording one supersedes the flag.
        assertNull(GiftCardLedger.recordFundingSubmitted(attempted, ID, TXID, LATER).single().fundingAttemptedAt)
        assertNull(GiftCardLedger.markFunded(attempted, ID, TXID, LATER).single().fundingAttemptedAt)
        // And a rejection clears it outright: nothing was sent.
        assertNull(GiftCardLedger.setFundingAttemptedAt(attempted, ID, null).single().fundingAttemptedAt)
        assertFalse(GiftCardLedger.hasUnsharedFunds(GiftCardLedger.setFundingAttemptedAt(attempted, ID, null)))
    }

    @Test
    fun `keeps the mnemonic out of toString`() {
        assertFalse(card().toString().contains(MNEMONIC))
    }

    private fun card(
        id: String = ID,
        amount: Long = 100_000_000L,
        status: GiftCardStatus = GiftCardStatus.DRAFT,
        txid: String? = null,
    ) = StoredGiftCard(
        id = id,
        network = "main",
        address = "u1exampleunifiedaddressforgiftcardtests",
        mnemonic = MNEMONIC,
        amountZatoshi = amount,
        birthdayHeight = 2_800_000L,
        sourceAccountUuid = ACCOUNT,
        createdAt = CREATED,
        updatedAt = CREATED,
        status = status,
        fundingTxid = txid,
    )

    private companion object {
        const val ID = "card-1"
        const val ACCOUNT = "account-1"
        const val TXID = "f00d"
        const val OTHER_TXID = "beef"
        const val CREATED = "2026-08-20T12:00:00Z"
        const val LATER = "2026-08-20T12:05:00Z"
        const val LATEST = "2026-08-20T12:09:00Z"

        /** BIP-39 test vector for all-zero entropy. Never a real wallet. */
        const val MNEMONIC =
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon art"
    }
}
