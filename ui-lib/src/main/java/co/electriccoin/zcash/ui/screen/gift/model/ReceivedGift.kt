// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.gift.model

import kotlinx.serialization.Serializable

/**
 * A gift this wallet is collecting, and — until its claim is final — the only way back to it.
 *
 * A broadcast that reached the mempool can expire or reorg. The link is written before broadcast,
 * kept with the isolated wallet database, and dropped only after SDK finality.
 * [address] is the identity, so one link cannot produce two receipts.
 */
@Serializable
data class ReceivedGift(
    val address: String,
    val network: String,
    val amountZatoshi: Long,
    val claimedAt: String,
    val destinationAddress: String? = null,
    /** Account that received the claim, persisted so confirmation never follows UI selection. */
    val destinationAccountUuid: String? = null,
    val claimTxids: List<String> = emptyList(),
    /** Written at the irreversible boundary, before entering create-and-submit. */
    val claimSubmissionAttemptedAt: String? = null,
    val message: String? = null,
    /** The bearer link, held until every [claimTxids] transaction reaches SDK finality. */
    val claimLink: GiftLinkPayload? = null,
    /** Durable cleanup checkpoint written before the isolated database is deleted. */
    val isFinalized: Boolean = false,
    /**
     * A scan found somebody else's final spend of this card, so there is nothing here to collect.
     *
     * Separate from [claimTxids], which records only what *this* wallet submitted: a card emptied
     * by another holder leaves that list empty forever, and without this flag the answer would have
     * to be rediscovered by a full rescan every time the link is opened again.
     */
    val isClaimedElsewhere: Boolean = false,
) {
    init {
        // A link for another network cannot retry this one. IAE so the store reads it as corrupt.
        require(claimLink == null || claimLink.network == network) {
            "Received gift link does not match its record"
        }
    }

    /** The claim is final, so nothing can need the link again. */
    val isSettled: Boolean
        get() = claimLink == null

    /**
     * Whether this wallet ever crossed the boundary into creating a claim transaction for this card.
     *
     * A receipt is written before the scan starts, so one exists for every card this wallet merely
     * *looked* at — an unfunded card, a card whose funding has not confirmed, a card another holder
     * is mid-claim on. None of those hold recovery material: nothing was created, so there is
     * nothing to recover, and the link inside is a copy of one the sender still holds and can send
     * again. Only past this boundary is a receipt custody, and only then may it keep a screen
     * reopening or a wallet undeletable.
     */
    val hasClaimAttempt: Boolean
        get() = claimSubmissionAttemptedAt != null || claimTxids.isNotEmpty()

    /** Unfinished *and* holding recovery material — the only receipts anything should act on. */
    val isUnsettledClaim: Boolean
        get() = !isSettled && hasClaimAttempt

    // The sender's words, an amount, and — while unsettled — the mnemonic.
    override fun toString(): String = "ReceivedGift(network=$network, redacted)"
}

/** Newest first, one receipt per card, and never regresses durable recovery state. */
internal fun List<ReceivedGift>.recording(gift: ReceivedGift): List<ReceivedGift> {
    val current = firstOrNull { it.address == gift.address }
    val merged =
        when {
            current == null -> {
                gift
            }

            current.isSettled -> {
                current
            }

            else -> {
                val startsNewSubmission =
                    gift.claimSubmissionAttemptedAt != null &&
                        gift.claimSubmissionAttemptedAt != current.claimSubmissionAttemptedAt &&
                        gift.claimTxids.isEmpty()
                gift.copy(
                    // An unsettled claim stays pinned to the account/address that received its first
                    // attempt. Following UI selection on a retry makes confirmation look in the
                    // wrong account and can split one card's transactions across accounts.
                    destinationAddress = current.destinationAddress ?: gift.destinationAddress,
                    destinationAccountUuid =
                        if (current.destinationAddress != null) {
                            current.destinationAccountUuid
                        } else {
                            gift.destinationAccountUuid
                        },
                    claimTxids =
                        if (startsNewSubmission) {
                            emptyList()
                        } else {
                            mergeClaimTxids(current.claimTxids, gift.claimTxids)
                        },
                    claimSubmissionAttemptedAt =
                        gift.claimSubmissionAttemptedAt ?: current.claimSubmissionAttemptedAt,
                    claimLink = current.claimLink ?: gift.claimLink,
                    isFinalized =
                        if (startsNewSubmission) {
                            false
                        } else {
                            current.isFinalized || gift.isFinalized
                        },
                    // Never cleared by a new submission: the card is empty whatever this wallet does.
                    isClaimedElsewhere = current.isClaimedElsewhere || gift.isClaimedElsewhere,
                )
            }
        }
    return listOf(merged) + filterNot { it.address == gift.address }
}

private fun mergeClaimTxids(current: List<String>, incoming: List<String>): List<String> =
    when {
        incoming.isEmpty() -> current
        current.isEmpty() -> incoming
        incoming.any(current::contains) -> (current + incoming).distinct()
        else -> incoming
    }

/** Drops the link for [address]. One-way, and a no-op if absent. */
internal fun List<ReceivedGift>.settling(address: String): List<ReceivedGift> =
    map { if (it.address == address) it.copy(claimLink = null) else it }

internal fun List<ReceivedGift>.finalizing(address: String): List<ReceivedGift> =
    map { if (it.address == address) it.copy(isFinalized = true) else it }

/**
 * Records that another holder emptied [address]. One-way, and a no-op if absent.
 *
 * Its own transition rather than part of [recording] because it is written after [settling], and a
 * settled receipt deliberately refuses further merges.
 */
internal fun List<ReceivedGift>.markingClaimedElsewhere(address: String): List<ReceivedGift> =
    map { if (it.address == address) it.copy(isClaimedElsewhere = true) else it }

/**
 * Drops the receipt for [address] when this wallet never started a claim against it.
 *
 * The only discard over this store, and the one that cannot lose anything: [ReceivedGift
 * .hasClaimAttempt] is false exactly when no transaction was created and none was broadcast, so
 * the record describes a card this wallet read and nothing more. Left behind, such a record is
 * indistinguishable from an interrupted claim — it reopens the claim screen on every foreground
 * and refuses every destructive action, for a gift that was never taken.
 *
 * Deliberately narrow. A settled receipt is history, a foreign-claim receipt is a terminal answer
 * worth keeping so the link need not be rescanned, and anything past the boundary is custody.
 */
internal fun List<ReceivedGift>.discardingUnstarted(address: String): List<ReceivedGift> =
    filterNot { it.address == address && !it.hasClaimAttempt && !it.isClaimedElsewhere && !it.isSettled }
