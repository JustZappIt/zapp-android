// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.gift.model

/** A mutation that would have lost track of a card's funds, refused. */
class GiftCardTransitionException(
    message: String,
) : IllegalStateException(message)

/**
 * The transition rules over the stored card list, kept pure so every one of them is directly
 * testable and so the storage provider is nothing but a mutex and a write.
 *
 * The invariants worth stating plainly, because each one is money:
 *
 *  - Status only ever advances. A card that regresses is a card the UI stops accounting for.
 *  - A card is never recorded [GiftCardStatus.FUNDED] without a funding txid.
 *  - A mutation never drops a record or rewrites its key material.
 *
 * There is a fourth state implied by the flow and absent from the enum: funding submitted but not
 * yet mined. It is represented as [GiftCardStatus.DRAFT] carrying a
 * [StoredGiftCard.fundingTxid] — see [recordFundingSubmitted]. That is what lets a sender share a
 * card in the ~75 seconds before its funding mines without the record claiming it has mined.
 */
object GiftCardLedger {
    /**
     * Persists a freshly minted card. Callers must complete this *before* submitting funding: a
     * crash in between otherwise loses the ephemeral seed, and with it the money, permanently.
     */
    fun add(cards: List<StoredGiftCard>, card: StoredGiftCard): List<StoredGiftCard> {
        ensure(cards.none { it.id == card.id }, "Gift card ${card.id} already exists")
        ensure(card.status == GiftCardStatus.DRAFT, "A new gift card starts as DRAFT")
        ensure(card.fundingTxid == null, "A new gift card has not been funded yet")
        return cards + card
    }

    /**
     * Records the txid of a submitted funding transaction, leaving the card [GiftCardStatus.DRAFT]
     * until it mines. Idempotent for the same txid so a retried submit is not an error.
     */
    fun recordFundingSubmitted(
        cards: List<StoredGiftCard>,
        id: String,
        fundingTxid: String,
        at: String,
    ): List<StoredGiftCard> =
        cards.replacing(id) { card ->
            ensure(fundingTxid.isNotBlank(), "Gift card $id needs a funding txid")
            ensure(
                card.fundingTxid == null || card.fundingTxid == fundingTxid,
                "Gift card $id is already funded by a different transaction"
            )
            card.copy(fundingTxid = fundingTxid, updatedAt = at)
        }

    /**
     * Marks a card funded once its transaction has mined. Requires the txid, which is the whole
     * point of the guard: a card recorded as funded with no transaction behind it is a card the
     * sender believes exists and the recipient cannot claim.
     */
    fun markFunded(
        cards: List<StoredGiftCard>,
        id: String,
        fundingTxid: String,
        at: String,
    ): List<StoredGiftCard> =
        cards.replacing(id) { card ->
            ensure(fundingTxid.isNotBlank(), "Gift card $id needs a funding txid")
            ensure(
                card.fundingTxid == null || card.fundingTxid == fundingTxid,
                "Gift card $id is already funded by a different transaction"
            )
            card.advancedTo(GiftCardStatus.FUNDED, at).copy(fundingTxid = fundingTxid)
        }

    /**
     * Marks the link as handed out. Requires a funding txid rather than [GiftCardStatus.FUNDED],
     * because the sender may share in the window between submit and the funding mining — but never
     * before there is a transaction at all, which would hand out a link to an empty address.
     */
    fun markShared(cards: List<StoredGiftCard>, id: String, at: String): List<StoredGiftCard> =
        cards.replacing(id) { card ->
            ensure(card.fundingTxid != null, "Gift card $id has not been funded yet")
            card.advancedTo(GiftCardStatus.SHARED, at)
        }

    /**
     * Hides a card from the active list. Deliberately keeps the record and its key material: for an
     * unshared card this is the only path back to the funds, and archiving is a tidying gesture,
     * not a decision to burn money.
     */
    fun archive(cards: List<StoredGiftCard>, id: String, at: String): List<StoredGiftCard> =
        cards.replacing(id) { card -> card.copy(archivedAt = card.archivedAt ?: at, updatedAt = at) }

    /**
     * Whether [accountUuid] still owns funds that only this device knows how to reach, which is the
     * condition that must block deleting the account.
     *
     * Archived cards count. Archiving hides a card; it does not move its money, and there is no
     * reclaim, so treating an archived card as settled would let the funds be destroyed silently.
     */
    fun hasUnsharedFunds(cards: List<StoredGiftCard>, accountUuid: String): Boolean =
        cards.any {
            it.sourceAccountUuid == accountUuid && it.fundingTxid != null && it.status != GiftCardStatus.SHARED
        }

    private fun List<StoredGiftCard>.replacing(
        id: String,
        transform: (StoredGiftCard) -> StoredGiftCard,
    ): List<StoredGiftCard> {
        ensure(any { it.id == id }, "No gift card $id")
        return map { if (it.id == id) transform(it) else it }
    }

    // Advances to *at least* [next]. Taking the maximum rather than assigning is what makes the
    // status monotonic by construction: a card whose funding mines after its link was shared gets
    // its confirmation recorded without being walked back out of SHARED, and no ordering of
    // callbacks can produce a regression for a later mutation to have to catch.
    private fun StoredGiftCard.advancedTo(next: GiftCardStatus, at: String): StoredGiftCard =
        copy(status = maxOf(status, next), updatedAt = at)

    private fun ensure(condition: Boolean, message: String) {
        if (!condition) throw GiftCardTransitionException(message)
    }
}
