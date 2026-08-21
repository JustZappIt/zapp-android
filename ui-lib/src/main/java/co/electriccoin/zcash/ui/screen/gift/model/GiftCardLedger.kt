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
 *  - A card is never recorded settled without evidence its funding reached the card.
 *  - A mutation never drops a record or rewrites its key material.
 *
 * The status is a *delivery* ordinal, not a description of the money. Funding submitted but not yet
 * mined has no rank of its own: it is [GiftCardStatus.DRAFT] carrying a [StoredGiftCard.fundingTxid]
 * — see [recordFundingSubmitted] — which is what lets a sender share a card in the ~75 seconds
 * before its funding mines without the record claiming it has mined. Because sharing outranks
 * [GiftCardStatus.FUNDED] and the ordinal only climbs, the confirmation itself is kept off the enum
 * entirely, in [StoredGiftCard.fundingMinedAt]. Every caller that needs to know whether the money is
 * really on the card asks [StoredGiftCard.isFundingMined], never the status.
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
     * Marks that a funding broadcast is about to be attempted, or — with a null [at] — that its
     * outcome is now known.
     *
     * This is what makes the broadcast crash-safe. The txid only exists once submit returns, so a
     * process killed mid-broadcast would otherwise leave a draft indistinguishable from one that
     * was never funded, and [hasUnsharedFunds] would not count money that had in fact left.
     */
    fun setFundingAttemptedAt(cards: List<StoredGiftCard>, id: String, at: String?): List<StoredGiftCard> =
        cards.replacing(id) { card -> card.copy(fundingAttemptedAt = at, updatedAt = at ?: card.updatedAt) }

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
            card.copy(fundingTxid = fundingTxid, fundingAttemptedAt = null, updatedAt = at)
        }

    /**
     * Marks a card funded once its transaction has mined. Requires the txid, which is the whole
     * point of the guard: a card recorded as funded with no transaction behind it is a card the
     * sender believes exists and the recipient cannot claim.
     *
     * Records [StoredGiftCard.fundingMinedAt] as well as advancing the status, and that is the half
     * that survives a card already past [GiftCardStatus.FUNDED]: a sender who shared during the
     * submit-to-mine window still gets the confirmation written down, where the status has no room
     * for it. First observation wins — the field is when the funding was *seen* to have mined, and
     * a second sweep over the same transaction is not a new event.
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
            card
                .advancedTo(GiftCardStatus.FUNDED, at)
                .copy(
                    fundingTxid = fundingTxid,
                    fundingAttemptedAt = null,
                    fundingMinedAt = card.fundingMinedAt ?: at,
                )
        }

    /**
     * Marks the link as handed out. Requires only that a broadcast was *started* — not a mined
     * funding, and not even a txid.
     *
     * The weakest of the three guards, deliberately. A sender may share in the window between
     * submit and the funding mining, and a card whose broadcast outcome was never seen
     * ([StoredGiftCard.fundingAttemptedAt] with no txid) has to be shareable too: its money may
     * already have gone, and then the link is the only route to it. Refusing that case would leave
     * the sender a card the UI offers to hand out and the ledger will not record — permanently
     * unshareable, permanently blocking the reset guard. What stays forbidden is the one case that
     * hands out a link to an address nothing was ever sent to.
     */
    fun markShared(cards: List<StoredGiftCard>, id: String, at: String): List<StoredGiftCard> =
        cards.replacing(id) { card ->
            ensure(card.hasFundingAttempt, "Gift card $id has not been funded yet")
            card.advancedTo(GiftCardStatus.SHARED, at)
        }

    /**
     * Records that the card was scanned and still held its funds. Carries no status change: the
     * card is exactly where it was, and the only new fact is when we last confirmed it.
     */
    fun recordChecked(cards: List<StoredGiftCard>, id: String, at: String): List<StoredGiftCard> =
        cards.replacing(id) { card -> card.copy(lastCheckedAt = at, updatedAt = at) }

    /**
     * Marks a card as collected, once its own wallet has been observed empty *having first held the
     * funding*.
     *
     * Both halves of that are load-bearing, and this is the transition where getting it wrong costs
     * money: settling is terminal, and a settled card can no longer be handed out, re-checked or
     * counted by the reset guard. An empty wallet on its own does not distinguish "somebody took
     * it" from "the funding never arrived" — a transaction still in the mempool, or one that was
     * dropped and may yet mine before it expires — so the caller must establish that the money
     * reached the card before calling this, and `CheckGiftCardClaimedUseCase` does that from the
     * card's own transaction history rather than from the status.
     *
     * The txid guard stays as the local half of the same check, and the observation is itself proof
     * the funding mined, so it backfills [StoredGiftCard.fundingMinedAt] for a card settled before
     * anything got round to confirming it.
     */
    fun markClaimed(cards: List<StoredGiftCard>, id: String, at: String): List<StoredGiftCard> =
        cards.replacing(id) { card ->
            ensure(card.fundingTxid != null, "Gift card $id has not been funded yet")
            card.advancedTo(GiftCardStatus.CLAIMED, at).copy(fundingMinedAt = card.fundingMinedAt ?: at)
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

/**
 * Whether [accountUuid] — or any account, when it is null — still owns funds that only this device
 * knows how to reach. That is the condition that must block deleting the account, and with a null
 * [accountUuid] the one that must block wiping the wallet, which clears them all.
 */
fun hasUnsharedFunds(cards: List<StoredGiftCard>, accountUuid: String? = null): Boolean =
    cards.any { it.isUnsharedFunds && (accountUuid == null || it.sourceAccountUuid == accountUuid) }
