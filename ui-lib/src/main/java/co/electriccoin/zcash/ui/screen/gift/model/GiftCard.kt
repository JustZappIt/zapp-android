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
    val archivedAt: String? = null,
) {
    // Same reasoning as GiftLinkPayload.toString.
    override fun toString(): String = "StoredGiftCard(id=$id, status=$status, redacted)"
}
