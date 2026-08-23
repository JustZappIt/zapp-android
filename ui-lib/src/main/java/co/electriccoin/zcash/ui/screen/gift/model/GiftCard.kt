// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.gift.model

import kotlinx.serialization.Serializable

/**
 * Bearer payload carried in a gift link's fragment.
 *
 * The shape is normative and shared with iOS, so the field names, the integer height and the
 * decimal-string amount are wire contract rather than local choices — `docs/gift-cards.md` §2.
 * [amountZatoshi] is a string because JSON numbers decode to doubles in too many parsers, which
 * would silently round a large card. The card's address is not carried: it is derived from
 * [mnemonic], so sending it would be 40% of the link spent restating what the link already says.
 */
@Serializable
data class GiftLinkPayload(
    val v: Int,
    val network: String,
    val amountZatoshi: String,
    val mnemonic: String,
    val birthdayHeight: Long,
    val createdAt: String,
    val expiresAt: String? = null,
    val message: String? = null,
) {
    // The mnemonic is the money, and a generated toString reaches every log line and crash report
    // that interpolates the payload.
    override fun toString(): String = "GiftLinkPayload(v=$v, network=$network, redacted)"
}

/**
 * Lifecycle of a locally minted card. Declaration order is the only legal direction of travel.
 *
 * [GiftCardLedger] advances a card by taking the maximum of its current and target status, so
 * regression is unrepresentable rather than merely checked — and a card that regressed is a card
 * the UI stops accounting for.
 */
enum class GiftCardStatus {
    /** Key material generated and persisted; nothing on chain yet. */
    DRAFT,

    /**
     * The funding transaction has mined. Never set without a txid.
     *
     * A card can reach [SHARED] without passing through here — sharing is legal from the moment
     * there is a broadcast — and the status only climbs, so a later confirmation cannot write this
     * rank back. Hence [StoredGiftCard.fundingMinedAt]: this rank says how far the card has got,
     * that field says whether the money is on it, and a collection check turns on the second.
     */
    FUNDED,

    /** The link has been handed to the share sheet at least once. */
    SHARED,

    /** A claim spend reached SDK finality. Terminal. */
    CLAIMED,
}

/**
 * The locally persisted half of a card, held in encrypted preferences.
 *
 * Custody-critical: the ephemeral seed is random rather than derived from the wallet seed and there
 * is no reclaim, so for an unshared card this record is the only recovery path. [network] and
 * [birthdayHeight] are stored rather than used once at creation because re-sharing rebuilds the
 * link from here, and both are required fields of [GiftLinkPayload].
 */
@Serializable
data class StoredGiftCard(
    val id: String,
    val network: String,
    val address: String,
    val mnemonic: String,
    val amountZatoshi: Long,
    val birthdayHeight: Long,
    val sourceAccountUuid: String,
    val createdAt: String,
    val updatedAt: String,
    val status: GiftCardStatus,
    val expiresAt: String? = null,
    val message: String? = null,
    val fundingTxid: String? = null,
    /**
     * When [fundingTxid] was created and stored locally, before any network submission.
     *
     * Null on records written before this phase existed. A legacy record with a txid is therefore
     * interpreted as submitted, while a new record with both fields can distinguish local creation
     * from money that may have left the sender's wallet.
     */
    val fundingCreatedAt: String? = null,
    /**
     * Set immediately before the funding transaction is broadcast and cleared once the outcome is
     * known. A record still carrying it is a card whose money may or may not have moved.
     */
    val fundingAttemptedAt: String? = null,
    /** A clean lightwalletd acceptance. Null while submission is unresolved or not yet attempted. */
    val fundingSubmittedAt: String? = null,
    /**
     * When the funding transaction was first observed with a block behind it.
     *
     * Orthogonal to [status] on purpose: that enum only climbs, so a card shared before its funding
     * mined has nowhere left to record the confirmation. See [isFundingMined].
     */
    val fundingMinedAt: String? = null,
    /**
     * When the card's own wallet was last scanned and found to still hold its funds. Only a
     * conclusive look sets it, so it is evidence rather than a record of having tried.
     */
    val lastCheckedAt: String? = null,
) {
    /**
     * A broadcast was started. Whether it landed is unanswerable for [fundingAttemptedAt] alone, so
     * this card must never be funded twice: the note may already be spent, and paying twice for one
     * gift is money gone twice.
     */
    val hasFundingAttempt: Boolean
        get() =
            fundingAttemptedAt != null ||
                fundingSubmittedAt != null ||
                // Backward compatibility: before fundingCreatedAt existed, a txid was only written
                // after the combined create-and-submit SDK call returned.
                (fundingTxid != null && fundingCreatedAt == null)

    /** The network accepted the transaction, including legacy records whose txid implied that. */
    val isFundingSubmitted: Boolean
        get() = fundingSubmittedAt != null || (fundingTxid != null && fundingCreatedAt == null)

    /**
     * A mint nothing was ever sent to, and nothing ever will be.
     *
     * The one state in which discarding a record discards nothing: no broadcast was started, so no
     * money can be at this address, so the seed unlocks nothing. A locally-created transaction may
     * exist in the SDK database, but `FundGiftCardUseCase` writes [fundingAttemptedAt] immediately
     * before submission and refuses the network call if that write fails. "No funding attempt" is
     * therefore a durable statement that no transaction was broadcast.
     *
     * Abandoned drafts are otherwise permanent: the sender who edits an amount and continues again
     * mints a second card, and the first is invisible to the list, unreachable from the UI, and
     * still occupying the one encrypted blob every mutation rewrites.
     */
    val isAbandonedDraft: Boolean
        get() = status == GiftCardStatus.DRAFT && !hasFundingAttempt

    /**
     * The funding is known to have mined, so the card really does hold its money.
     *
     * [GiftCardStatus.SHARED] is deliberately not evidence — a sender may share between submit and
     * mining. Records written before [fundingMinedAt] existed fall back to the ranks that could
     * only have come from a confirmation, so a shared one reads as unconfirmed and gets picked up
     * by the next reconciliation: one extra lookup, never a wrong answer.
     */
    val isFundingMined: Boolean
        get() = fundingMinedAt != null || status == GiftCardStatus.FUNDED || status == GiftCardStatus.CLAIMED

    /**
     * Money has left the sender's wallet — or may have — and the link has not left the device, so
     * this record is the only route back to it. A txid rather than [GiftCardStatus.FUNDED] because
     * a submitted transaction is already spent, and [fundingAttemptedAt] too because a broadcast
     * nobody saw the end of has to be assumed to have landed.
     */
    val isUnsharedFunds: Boolean
        get() = hasFundingAttempt && status < GiftCardStatus.SHARED

    // Same reasoning as GiftLinkPayload.toString.
    override fun toString(): String = "StoredGiftCard(id=$id, status=$status, redacted)"
}

/** Rebuilds the shareable payload. The record is the source of truth for the link, not the reverse. */
fun StoredGiftCard.toLinkPayload(): GiftLinkPayload =
    GiftLinkPayload(
        v = GiftLinkCodec.VERSION,
        network = network,
        amountZatoshi = amountZatoshi.toString(),
        mnemonic = mnemonic,
        birthdayHeight = birthdayHeight,
        createdAt = createdAt,
        expiresAt = expiresAt,
        message = message,
    )
