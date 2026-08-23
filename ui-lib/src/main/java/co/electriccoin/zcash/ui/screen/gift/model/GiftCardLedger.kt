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
 * The invariants, each of which is money:
 *
 *  - Status only ever advances. A card that regresses is a card the UI stops accounting for.
 *  - A card is never recorded [GiftCardStatus.FUNDED] without a funding txid.
 *  - A card is never recorded settled without evidence its funding reached the card.
 *  - A mutation never drops a record or rewrites its key material.
 *
 * The status is a *delivery* ordinal, not a description of the money: funding submitted but not yet
 * mined is [GiftCardStatus.DRAFT] carrying a [StoredGiftCard.fundingTxid]. That is what lets a
 * sender share in the ~75 seconds before the funding mines without the record claiming it has. The
 * confirmation itself lives off the enum, in [StoredGiftCard.fundingMinedAt], and every caller that
 * needs to know whether the money is really on the card asks [StoredGiftCard.isFundingMined].
 */
object GiftCardLedger {
    /**
     * Persists a freshly minted card. Callers must complete this *before* submitting funding: a
     * crash in between otherwise loses the ephemeral seed, and with it the money, permanently.
     *
     * Minting supersedes any [StoredGiftCard.isAbandonedDraft] already on file, which is the only
     * discard in this object and the only one that can be: a draft with no funding attempt is a
     * record of an address no transaction was ever sent to. Without it every edited amount leaves
     * one behind for good — a store that only grows, holding key material that unlocks nothing, in
     * the single blob each mutation reads and rewrites.
     *
     * Tied to minting rather than run on a timer on purpose. A sweep would have to decide when a
     * draft is old enough to be dead, and getting that wrong is unrecoverable; here the answer is
     * structural, and the mutex around this makes the read-and-replace atomic.
     */
    fun add(cards: List<StoredGiftCard>, card: StoredGiftCard): List<StoredGiftCard> {
        ensure(cards.none { it.id == card.id }, "Gift card ${card.id} already exists")
        ensure(card.status == GiftCardStatus.DRAFT, "A new gift card starts as DRAFT")
        ensure(card.fundingTxid == null, "A new gift card has not been funded yet")
        return cards.filterNot { it.isAbandonedDraft } + card
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
     * until it mines. Idempotent for the same txid, so a retried submit is not an error.
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
     * Marks a card funded once its transaction has mined. The txid guard is the point: a card
     * recorded as funded with no transaction behind it is one the sender believes exists and the
     * recipient cannot claim.
     *
     * Also records [StoredGiftCard.fundingMinedAt], which is the half that survives a card already
     * past [GiftCardStatus.FUNDED]. First observation wins — the field is when the funding was
     * *seen* to have mined, and a second sweep over the same transaction is not a new event.
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
     * The weakest of the three guards, deliberately. A card whose broadcast outcome was never seen
     * has to be shareable too: its money may already have gone, and then the link is the only route
     * to it. Refusing that would leave a card the UI offers to hand out and the ledger will not
     * record — permanently unshareable, permanently blocking the reset guard. What stays forbidden
     * is the one case that hands out a link to an address nothing was ever sent to.
     */
    fun markShared(cards: List<StoredGiftCard>, id: String, at: String): List<StoredGiftCard> =
        cards.replacing(id) { card ->
            ensure(card.hasFundingAttempt, "Gift card $id has not been funded yet")
            card.advancedTo(GiftCardStatus.SHARED, at)
        }

    /** Records that the card was scanned and still held its funds. No status change: nothing moved. */
    fun recordChecked(cards: List<StoredGiftCard>, id: String, at: String): List<StoredGiftCard> =
        cards.replacing(id) { card -> card.copy(lastCheckedAt = at, updatedAt = at) }

    /**
     * Marks a card collected after its funding and finalized claim spend are observed. The
     * observation also backfills [StoredGiftCard.fundingMinedAt].
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

    // Taking the maximum rather than assigning is what makes the status monotonic by construction:
    // a card whose funding mines after its link was shared gets the confirmation recorded without
    // being walked back out of SHARED, and no ordering of callbacks can produce a regression.
    private fun StoredGiftCard.advancedTo(next: GiftCardStatus, at: String): StoredGiftCard =
        copy(status = maxOf(status, next), updatedAt = at)

    private fun ensure(condition: Boolean, message: String) {
        if (!condition) throw GiftCardTransitionException(message)
    }
}

/**
 * Whether [accountUuid] — or any account, when it is null — still owns funds that only this device
 * knows how to reach. Blocks deleting the account, and with a null [accountUuid] blocks the wallet
 * wipe, which clears them all.
 */
fun hasUnsharedFunds(cards: List<StoredGiftCard>, accountUuid: String? = null): Boolean =
    cards.any { it.isUnsharedFunds && (accountUuid == null || it.sourceAccountUuid == accountUuid) }
