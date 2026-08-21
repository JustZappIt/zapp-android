// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.gift.model

import kotlinx.serialization.Serializable

/**
 * Bearer payload carried in a gift link's fragment.
 *
 * This shape is normative and shared with iOS — a card minted on one platform must claim on the
 * other — so the field names, the integer height and the decimal-string amount are all part of the
 * wire contract, not local implementation choices. See `docs/gift-cards.md` §2.
 *
 * [amountZatoshi] is a string because JSON numbers decode to doubles in too many parsers, which
 * would silently round a large card.
 */
@Serializable
data class GiftLinkPayload(
    val v: Int,
    val network: String,
    val address: String,
    val amountZatoshi: String,
    val mnemonic: String,
    val birthdayHeight: Long,
    val createdAt: String,
    val expiresAt: String? = null,
    val message: String? = null,
) {
    // The mnemonic is the money: a generated toString would drop it into any log line, crash
    // report or exception message that interpolates the payload.
    override fun toString(): String = "GiftLinkPayload(v=$v, network=$network, redacted)"
}

/**
 * Lifecycle of a locally minted card.
 *
 * Declaration order is the only legal direction of travel, and `GiftCardLedger` advances a card by
 * taking the maximum of its current and target status rather than assigning one — regression is
 * unrepresentable rather than merely checked. A card that regressed would be a card whose funds the
 * UI stops accounting for.
 */
enum class GiftCardStatus {
    /** Key material generated and persisted; nothing on chain yet. */
    DRAFT,

    /**
     * The funding transaction has mined. Never set without a txid.
     *
     * A card can reach [SHARED] without passing through here — sharing is legal from the moment
     * there is a broadcast — and because the status only ever advances, a later confirmation cannot
     * write this rank back. That is why "the funding mined" is *also* recorded as its own fact in
     * [StoredGiftCard.fundingMinedAt]: this rank answers "how far has the card got", and that field
     * answers "is the money actually on it", which is the question a collection check turns on.
     */
    FUNDED,

    /** The link has been handed to the share sheet at least once. */
    SHARED,

    /**
     * The card's own wallet is empty again, so whoever held the link took the money. Terminal, and
     * the only status that settles a card: there is nothing left to hand out or to lose.
     */
    CLAIMED,
}

/**
 * The locally persisted half of a card, held in encrypted preferences.
 *
 * This record is custody-critical: the ephemeral seed is random rather than derived from the wallet
 * seed, and there is no reclaim, so for an unshared card this is the *only* recovery path. Losing
 * or corrupting it loses real money.
 *
 * [network] and [birthdayHeight] are stored — not just used once at creation — because re-sharing a
 * funded card rebuilds its link from this record, and both are required fields of [GiftLinkPayload].
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
     * Set immediately before the funding transaction is broadcast and cleared once the outcome is
     * known. A record still carrying it is a card whose money may or may not have moved — the
     * process died mid-broadcast, or the submit came back uncertain.
     */
    val fundingAttemptedAt: String? = null,
    /**
     * When the funding transaction was first observed with a block behind it.
     *
     * Orthogonal to [status] on purpose. Delivery and settlement advance that enum, and it only
     * ever advances, so a card shared before its funding mined has nowhere left to record the
     * confirmation. Money-on-the-card is a fact about the chain rather than a stage of the flow,
     * and this is where it lives — see [isFundingMined].
     */
    val fundingMinedAt: String? = null,
    /**
     * When the card's own wallet was last scanned and found to still hold its funds. Only a
     * conclusive look sets it, so it is evidence of "still unclaimed as of then" rather than of
     * having tried.
     */
    val lastCheckedAt: String? = null,
) {
    /**
     * A broadcast was started for this card. Whether it landed is a separate question — and an
     * unanswerable one for [fundingAttemptedAt] — so this card must never be funded a second time:
     * the note may already be spent, and paying twice for one gift is money gone twice.
     */
    val hasFundingAttempt: Boolean
        get() = fundingTxid != null || fundingAttemptedAt != null

    /**
     * The funding is known to have mined, so the card really does hold its money.
     *
     * [GiftCardStatus.SHARED] is deliberately not evidence: a sender may share in the window
     * between submit and the funding mining, so that rank says only that the link went out. Records
     * written before [fundingMinedAt] existed fall back to the ranks that could only ever have been
     * set from a confirmation — a [GiftCardStatus.SHARED] one reads as unconfirmed and gets picked
     * up by the next reconciliation, which costs a lookup and never a wrong answer.
     */
    val isFundingMined: Boolean
        get() = fundingMinedAt != null || status == GiftCardStatus.FUNDED || status == GiftCardStatus.CLAIMED

    /**
     * Money has left the sender's wallet — or may have — and the link has not left the device, so
     * this record is the only route back to it. A txid rather than [GiftCardStatus.FUNDED], because
     * a submitted transaction is already spent; [fundingAttemptedAt] too, because a broadcast whose
     * outcome nobody saw has to be assumed to have landed.
     */
    val isUnsharedFunds: Boolean
        get() = hasFundingAttempt && status < GiftCardStatus.SHARED

    // Same reasoning as GiftLinkPayload.toString.
    override fun toString(): String = "StoredGiftCard(id=$id, status=$status, redacted)"
}

/**
 * Rebuilds the shareable payload from the persisted record.
 *
 * The record is the source of truth for the link, not the other way round: a funded card is
 * re-shared by encoding this again, which is why [StoredGiftCard] carries [StoredGiftCard.network]
 * and [StoredGiftCard.birthdayHeight] rather than using them once at creation.
 */
fun StoredGiftCard.toLinkPayload(): GiftLinkPayload =
    GiftLinkPayload(
        v = GiftLinkCodec.VERSION,
        network = network,
        address = address,
        amountZatoshi = amountZatoshi.toString(),
        mnemonic = mnemonic,
        birthdayHeight = birthdayHeight,
        createdAt = createdAt,
        expiresAt = expiresAt,
        message = message,
    )
