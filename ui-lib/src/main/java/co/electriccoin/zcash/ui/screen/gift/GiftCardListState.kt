// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.gift

import co.electriccoin.zcash.ui.design.util.StringResource

/**
 * How far a stored card got, flattened for display.
 *
 * [SUBMITTED] and [UNRESOLVED] are the states the enum on disk cannot express — a draft carrying a
 * funding txid, and one carrying only an unresolved broadcast attempt. Both matter here because
 * either may already hold real money.
 */
internal enum class GiftCardListStatus {
    UNFUNDED,

    /** Broadcast started and its outcome was never seen. Treated as money gone until proven otherwise. */
    UNRESOLVED,
    SUBMITTED,
    FUNDED,
    SHARED,

    /** Collected by whoever held the link. Terminal: nothing left to hand out or to lose. */
    CLAIMED,
}

internal enum class GiftCardListError {
    LINK_FAILED,
    SHARE_FAILED,

    /** The scan could not finish, which says nothing about whether the card was collected. */
    CHECK_FAILED,
}

/**
 * One row. Deliberately carries no mnemonic and no link: the link is rebuilt from storage only when
 * the sender asks for it, so a screenshot or a state dump of this list is not a bearer secret.
 */
internal data class GiftCardListItem(
    val id: String,
    val amount: StringResource,
    val createdAt: StringResource?,
    val message: String?,
    val status: GiftCardListStatus,
    val expiry: GiftExpiryDisplay?,
    val isArchived: Boolean,
    val isCopied: Boolean,
    /** Null once a card is settled, or while another card is being checked. */
    val onCheck: (() -> Unit)?,
    val isChecking: Boolean,
    /** Both null while the card holds nothing to hand over — see `GiftCardListVM.toItem`. */
    val onCopy: (() -> Unit)?,
    val onShare: ((String) -> Unit)?,
    val onArchive: (() -> Unit)?,
)

/** A gift collected from someone else. A receipt only — it carries no key material. */
internal data class ReceivedGiftItem(
    val address: String,
    val amount: StringResource,
    val claimedAt: StringResource?,
    val message: String?,
)

internal data class GiftCardListState(
    val items: List<GiftCardListItem>,
    val received: List<ReceivedGiftItem>,
    val isCorrupted: Boolean,
    val hasArchived: Boolean,
    val isShowingArchived: Boolean,
    val error: GiftCardListError?,
    val onToggleArchived: () -> Unit,
    val onBack: () -> Unit,
)
