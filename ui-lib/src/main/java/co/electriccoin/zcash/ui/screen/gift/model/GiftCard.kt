// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.gift.model

import kotlinx.serialization.Serializable

/**
 * Bearer payload carried in a gift link's fragment.
 *
 * This shape is normative and shared with iOS — a card minted on one platform must claim on the
 * other — so the field names, the integer height and the decimal-string amount are all part of the
 * wire contract, not local implementation choices. See `docs/GIFT_CARDS_PLAN.md` §2.
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

    /** The funding transaction has mined. Never set without a txid. */
    FUNDED,

    /** The link has been handed to the share sheet at least once. */
    SHARED,
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
    val archivedAt: String? = null,
) {
    /**
     * A broadcast was started for this card. Whether it landed is a separate question — and an
     * unanswerable one for [fundingAttemptedAt] — so this card must never be funded a second time:
     * the note may already be spent, and paying twice for one gift is money gone twice.
     */
    val hasFundingAttempt: Boolean
        get() = fundingTxid != null || fundingAttemptedAt != null

    /**
     * Money has left the sender's wallet — or may have — and the link has not left the device, so
     * this record is the only route back to it. A txid rather than [GiftCardStatus.FUNDED], because
     * a submitted transaction is already spent; [fundingAttemptedAt] too, because a broadcast whose
     * outcome nobody saw has to be assumed to have landed; archived cards included, because
     * archiving moves no money.
     */
    val isUnsharedFunds: Boolean
        get() = hasFundingAttempt && status != GiftCardStatus.SHARED

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
