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
@Suppress("TooManyFunctions")
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
        ensure(!card.hasFundingHistory, "A new gift card has no funding history")
        return cards.filterNot { it.isAbandonedDraft } + card
    }

    /**
     * Marks that the SDK funding pipeline is about to be started.
     *
     * This is what makes the SDK boundary crash-safe. It is written before local creation because
     * Slipstream may automatically submit any outgoing transaction stored in its database, even
     * when the app has not called `Broadcaster.submit` yet.
     */
    fun setFundingAttemptedAt(cards: List<StoredGiftCard>, id: String, at: String): List<StoredGiftCard> =
        cards.replacing(id) { card ->
            ensure(!card.hasFundingAttempt, "Gift card $id funding was already started")
            ensure(!card.isFundingMined, "Gift card $id is already funded")
            ensure(card.status != GiftCardStatus.CLAIMED, "Gift card $id is already collected")
            card.copy(
                fundingTxid = null,
                fundingCreatedAt = null,
                fundingAttemptedAt = at,
                fundingSubmittedAt = null,
                updatedAt = at,
            )
        }

    /** Attaches the txid created after [setFundingAttemptedAt] established the durable gate. */
    fun recordFundingCreated(
        cards: List<StoredGiftCard>,
        id: String,
        fundingTxid: String,
        at: String,
    ): List<StoredGiftCard> =
        cards.replacing(id) { card ->
            ensure(card.fundingAttemptedAt != null, "Gift card $id funding was not started durably")
            ensure(card.fundingTxid == null, "Gift card $id already has a funding transaction")
            ensure(fundingTxid.isNotBlank(), "Gift card $id needs a funding txid")
            ensure(
                card.fundingFailures.none { it.transactionId == fundingTxid },
                "Gift card $id cannot reactivate an expired funding transaction"
            )
            card.copy(
                fundingTxid = fundingTxid,
                fundingCreatedAt = at,
                fundingAttemptedAt = card.fundingAttemptedAt,
                fundingSubmittedAt = null,
                updatedAt = at,
            )
        }

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
            ensure(
                card.fundingFailures.none { it.transactionId == fundingTxid },
                "Gift card $id cannot resubmit an expired funding transaction"
            )
            card.copy(
                fundingTxid = fundingTxid,
                fundingCreatedAt = card.fundingCreatedAt,
                fundingAttemptedAt = null,
                fundingSubmittedAt = at,
                updatedAt = at,
            )
        }

    /**
     * Resolves an attempt whose transaction was never created, after a fully-synced wallet read.
     * The history record keeps a shared card visible while clearing the double-funding gate.
     */
    fun markFundingNotCreated(cards: List<StoredGiftCard>, id: String, at: String): List<StoredGiftCard> =
        cards.replacing(id) { card ->
            val lifecycle = card.fundingLifecycle
            if (lifecycle is GiftFundingLifecycle.Retryable &&
                lifecycle.lastFailure.reason == GiftFundingFailureReason.NO_TRANSACTION
            ) {
                return@replacing card
            }
            val attempt =
                lifecycle as? GiftFundingLifecycle.Attempting
                    ?: throw GiftCardTransitionException("Gift card $id is not awaiting transaction creation")
            card.withFailedFunding(
                failures =
                    listOf(
                        GiftFundingFailure(
                            reason = GiftFundingFailureReason.NO_TRANSACTION,
                            attemptedAt = attempt.attemptedAt,
                            detectedAt = at,
                        )
                    ),
                at = at,
            )
        }

    /**
     * Resolves every expired transaction belonging to the current attempt.
     *
     * Keeping all ids matters after repeated recovery: an old expired row must never be selected as
     * the active transaction of a later attempt merely because it still targets the same address.
     */
    fun markFundingExpired(
        cards: List<StoredGiftCard>,
        id: String,
        fundingTxids: Set<String>,
        at: String,
    ): List<StoredGiftCard> =
        cards.replacing(id) { card ->
            ensure(fundingTxids.isNotEmpty(), "Gift card $id needs an expired funding txid")
            ensure(!card.isFundingMined, "Gift card $id funding already mined")
            ensure(card.status != GiftCardStatus.CLAIMED, "Gift card $id is already collected")
            card.fundingTxid?.let {
                ensure(it in fundingTxids, "Gift card $id active funding transaction has not expired")
            }
            val attemptedAt = card.currentFundingAttemptedAt()
            val failures =
                fundingTxids
                    .filterNot { txid -> card.fundingFailures.any { it.transactionId == txid } }
                    .sorted()
                    .map { txid ->
                        GiftFundingFailure(
                            reason = GiftFundingFailureReason.EXPIRED,
                            attemptedAt = attemptedAt,
                            transactionId = txid,
                            detectedAt = at,
                        )
                    }
            if (failures.isEmpty() && card.isFundingRetryable) return@replacing card
            ensure(failures.isNotEmpty(), "Gift card $id has no new expired funding transaction")
            card.withFailedFunding(failures, at)
        }

    /**
     * Archives expired candidates and attaches the single still-live transaction atomically.
     * This is recovery for a process death between SDK creation and recording the new txid.
     */
    fun replaceExpiredFunding(
        cards: List<StoredGiftCard>,
        id: String,
        expiredFundingTxids: Set<String>,
        activeFundingTxid: String,
        at: String,
    ): List<StoredGiftCard> =
        cards.replacing(id) { card ->
            ensure(activeFundingTxid.isNotBlank(), "Gift card $id needs an active funding txid")
            ensure(activeFundingTxid !in expiredFundingTxids, "Gift card $id active transaction cannot be expired")
            ensure(
                card.fundingFailures.none { it.transactionId == activeFundingTxid },
                "Gift card $id cannot reactivate an expired funding transaction"
            )
            ensure(!card.isFundingMined, "Gift card $id funding already mined")
            card.fundingTxid?.let {
                ensure(it in expiredFundingTxids, "Gift card $id cannot replace a live funding transaction")
            }
            val attemptedAt = card.currentFundingAttemptedAt()
            val failures =
                expiredFundingTxids
                    .filterNot { txid -> card.fundingFailures.any { it.transactionId == txid } }
                    .sorted()
                    .map { txid ->
                        GiftFundingFailure(
                            reason = GiftFundingFailureReason.EXPIRED,
                            attemptedAt = attemptedAt,
                            transactionId = txid,
                            detectedAt = at,
                        )
                    }
            card.copy(
                fundingTxid = activeFundingTxid,
                fundingCreatedAt = at,
                fundingAttemptedAt = attemptedAt ?: at,
                fundingSubmittedAt = null,
                fundingFailures = card.fundingFailures + failures,
                updatedAt = at,
            )
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
            ensure(
                card.fundingFailures.none { it.transactionId == fundingTxid },
                "Gift card $id cannot mine an expired funding transaction"
            )
            // The transaction is attached before the status advances. `copy` runs the record's
            // invariants, and a card momentarily FUNDED with no txid behind it is precisely what
            // they refuse — so advancing first throws on the very card this is here to confirm.
            card
                .copy(
                    fundingTxid = fundingTxid,
                    fundingAttemptedAt = null,
                    fundingSubmittedAt = card.fundingSubmittedAt ?: at,
                    fundingMinedAt = card.fundingMinedAt ?: at,
                ).advancedTo(GiftCardStatus.FUNDED, at)
        }

    /**
     * Marks the link as handed out. Requires only that a broadcast was *started*, not that funding
     * has mined or that the submission outcome was observed.
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

    private fun StoredGiftCard.currentFundingAttemptedAt(): String? =
        fundingAttemptedAt ?: fundingCreatedAt ?: fundingSubmittedAt

    private fun StoredGiftCard.withFailedFunding(
        failures: List<GiftFundingFailure>,
        at: String,
    ) =
        copy(
            fundingTxid = null,
            fundingCreatedAt = null,
            fundingAttemptedAt = null,
            fundingSubmittedAt = null,
            fundingFailures = fundingFailures + failures,
            updatedAt = at,
        )

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
