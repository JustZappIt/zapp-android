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
}

internal enum class GiftCardListError {
    LINK_FAILED,
    SHARE_FAILED,
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
    val isArchived: Boolean,
    val isCopied: Boolean,
    /** Both null while the card holds nothing to hand over — see `GiftCardListVM.toItem`. */
    val onCopy: (() -> Unit)?,
    val onShare: ((String) -> Unit)?,
    val onArchive: (() -> Unit)?,
)

internal data class GiftCardListState(
    val items: List<GiftCardListItem>,
    val isCorrupted: Boolean,
    val hasArchived: Boolean,
    val isShowingArchived: Boolean,
    val error: GiftCardListError?,
    val onToggleArchived: () -> Unit,
    val onBack: () -> Unit,
)
