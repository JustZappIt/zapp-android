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
    fun `shares a card whose broadcast outcome was never seen`() {
        val unresolved = listOf(card(attemptedAt = LATER))

        // The money may already have left for this card, and then the link is the only route to it.
        // Refusing here left the list offering a hand-off the ledger would not record: shared in the
        // chooser, unshared on disk, and blocking a wallet reset for good.
        assertEquals(GiftCardStatus.SHARED, GiftCardLedger.markShared(unresolved, ID, LATER).single().status)
    }

    @Test
    fun `records that funding mined on a card already shared`() {
        val shared = GiftCardLedger.markShared(submitted(), ID, LATER)

        val funded = GiftCardLedger.markFunded(shared, ID, TXID, LATEST).single()

        // The status cannot regress out of SHARED to say this, so the confirmation lives beside it.
        // Without it nothing could ever tell a shared card holding money from one whose funding was
        // still in the mempool — and a collection check turns on exactly that difference.
        assertEquals(GiftCardStatus.SHARED, funded.status)
        assertEquals(LATEST, funded.fundingMinedAt)
        assertTrue(funded.isFundingMined)
    }

    @Test
    fun `keeps the first confirmation when funding is swept twice`() {
        val funded = GiftCardLedger.markFunded(listOf(card(txid = TXID)), ID, TXID, LATER)

        // When it was seen to have mined, not when something last looked.
        assertEquals(LATER, GiftCardLedger.markFunded(funded, ID, TXID, LATEST).single().fundingMinedAt)
    }

    @Test
    fun `a shared card is not evidence its funding mined`() {
        val shared = GiftCardLedger.markShared(submitted(), ID, LATER)

        // Sharing is legal in the window between submit and the funding mining, so the rank says
        // only that the link went out.
        assertFalse(shared.single().isFundingMined)
    }

    @Test
    fun `sharing twice is not an error`() {
        val once = GiftCardLedger.markShared(GiftCardLedger.markFunded(listOf(card()), ID, TXID, LATER), ID, LATER)

        val twice = GiftCardLedger.markShared(once, ID, LATER)

        assertEquals(GiftCardStatus.SHARED, twice.single().status)
    }

    @Test
    fun `rejects a mutation naming a card that is not there`() {
        assertFailsWith<GiftCardTransitionException> { GiftCardLedger.markFunded(listOf(card()), "nope", TXID, LATER) }
        assertFailsWith<GiftCardTransitionException> { GiftCardLedger.markShared(listOf(card()), "nope", LATER) }
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

        assertFalse(hasUnsharedFunds(listOf(draft), ACCOUNT))
        assertTrue(hasUnsharedFunds(submitted, ACCOUNT))
        assertTrue(hasUnsharedFunds(funded, ACCOUNT))
        assertFalse(hasUnsharedFunds(shared, ACCOUNT))
    }

    @Test
    fun `scopes unshared funds to the owning account`() {
        val funded = GiftCardLedger.markFunded(listOf(card()), ID, TXID, LATER)

        assertTrue(hasUnsharedFunds(funded, ACCOUNT))
        assertFalse(hasUnsharedFunds(funded, "some-other-account"))
    }

    @Test
    fun `sees unshared funds in any account when asked wallet-wide`() {
        val funded = GiftCardLedger.markFunded(listOf(card()), ID, TXID, LATER)

        // What a wallet wipe has to ask: it clears every account's cards at once.
        assertTrue(hasUnsharedFunds(funded))
        assertFalse(hasUnsharedFunds(GiftCardLedger.markShared(funded, ID, LATER)))
        assertFalse(hasUnsharedFunds(listOf(card())))
        assertFalse(hasUnsharedFunds(emptyList()))
    }

    @Test
    fun `counts a broadcast whose outcome was never seen as unshared funds`() {
        val attempted = GiftCardLedger.setFundingAttemptedAt(listOf(card()), ID, LATER)

        // No txid was ever written, but the money may already have left. Guessing "unfunded" here
        // is what would let the wallet be wiped out from under it.
        assertNull(attempted.single().fundingTxid)
        assertTrue(hasUnsharedFunds(attempted))
        assertTrue(hasUnsharedFunds(attempted, ACCOUNT))
    }

    @Test
    fun `clears the attempt once its outcome is known`() {
        val attempted = GiftCardLedger.setFundingAttemptedAt(listOf(card()), ID, LATER)

        // A txid is a stronger record of the same fact, so recording one supersedes the flag.
        assertNull(GiftCardLedger.recordFundingSubmitted(attempted, ID, TXID, LATER).single().fundingAttemptedAt)
        assertNull(GiftCardLedger.markFunded(attempted, ID, TXID, LATER).single().fundingAttemptedAt)
        // And a rejection clears it outright: nothing was sent.
        assertNull(GiftCardLedger.setFundingAttemptedAt(attempted, ID, null).single().fundingAttemptedAt)
        assertFalse(hasUnsharedFunds(GiftCardLedger.setFundingAttemptedAt(attempted, ID, null)))
    }

    @Test
    fun `keeps the mnemonic out of toString`() {
        assertFalse(card().toString().contains(MNEMONIC))
    }

    @Test
    fun `records a check without moving the card`() {
        val shared = GiftCardLedger.markShared(GiftCardLedger.markFunded(listOf(card()), ID, TXID, LATER), ID, LATER)

        val checked = GiftCardLedger.recordChecked(shared, ID, LATEST).single()

        // The only new fact is when we last confirmed the funds were still there.
        assertEquals(LATEST, checked.lastCheckedAt)
        assertEquals(GiftCardStatus.SHARED, checked.status)
        assertEquals(TXID, checked.fundingTxid)
    }

    @Test
    fun `marks a shared card collected`() {
        val cards = listOf(card(status = GiftCardStatus.SHARED, txid = TXID))

        val claimed = GiftCardLedger.markClaimed(cards, ID, LATER).single()

        assertEquals(GiftCardStatus.CLAIMED, claimed.status)
    }

    @Test
    fun `settling a card records that its funding must have mined`() {
        val cards = listOf(card(status = GiftCardStatus.SHARED, txid = TXID))

        val claimed = GiftCardLedger.markClaimed(cards, ID, LATER).single()

        // An emptied wallet cannot have been emptied before it was filled, so the observation
        // backfills the confirmation nothing got round to recording.
        assertEquals(LATER, claimed.fundingMinedAt)
    }

    @Test
    fun `refuses to mark an unfunded card collected`() {
        // An empty wallet on a card that was never funded means nobody took anything.
        assertFailsWith<GiftCardTransitionException> {
            GiftCardLedger.markClaimed(listOf(card()), ID, LATER)
        }
    }

    @Test
    fun `a collected card no longer holds unshared funds`() {
        val cards = GiftCardLedger.markClaimed(listOf(card(status = GiftCardStatus.SHARED, txid = TXID)), ID, LATER)

        // Blocking a wallet reset over money somebody already took would be a false alarm.
        assertFalse(hasUnsharedFunds(cards))
    }

    @Test
    fun `a collected card cannot regress to shared`() {
        val cards = GiftCardLedger.markClaimed(listOf(card(status = GiftCardStatus.SHARED, txid = TXID)), ID, LATER)

        val reshared = GiftCardLedger.markShared(cards, ID, LATEST).single()

        assertEquals(GiftCardStatus.CLAIMED, reshared.status)
    }

    @Test
    fun `minting supersedes a draft nothing was ever sent to`() {
        val abandoned = GiftCardLedger.add(emptyList(), card())

        val cards = GiftCardLedger.add(abandoned, card(id = OTHER_ID))

        // Its address never saw a transaction, so the seed unlocks nothing and keeping the record
        // only grows the one blob every mutation rewrites.
        assertEquals(listOf(OTHER_ID), cards.map { it.id })
    }

    @Test
    fun `minting never discards a draft whose broadcast was started`() {
        val flagged = GiftCardLedger.setFundingAttemptedAt(listOf(card()), ID, LATER)

        val cards = GiftCardLedger.add(flagged, card(id = OTHER_ID))

        // The money may already have left, and this record is the only route back to it.
        assertEquals(listOf(ID, OTHER_ID), cards.map { it.id })
    }

    @Test
    fun `minting never discards a card that reached any later status`() {
        val kept = listOf(card(id = "funded", status = GiftCardStatus.FUNDED, txid = TXID))

        val cards = GiftCardLedger.add(kept, card())

        assertEquals(listOf("funded", ID), cards.map { it.id })
    }

    /** One draft carrying a submitted funding txid — the state a card shares from. */
    private fun submitted() = GiftCardLedger.recordFundingSubmitted(listOf(card()), ID, TXID, LATER)

    private fun card(
        id: String = ID,
        amount: Long = 100_000_000L,
        status: GiftCardStatus = GiftCardStatus.DRAFT,
        txid: String? = null,
        attemptedAt: String? = null,
        minedAt: String? = null,
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
        fundingAttemptedAt = attemptedAt,
        fundingMinedAt = minedAt,
    )

    private companion object {
        const val ID = "card-1"
        const val OTHER_ID = "card-2"
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
