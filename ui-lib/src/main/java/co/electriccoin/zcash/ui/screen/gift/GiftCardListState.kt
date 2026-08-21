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

/**
 * How far a running check has got.
 *
 * Its existence is the signal that the scan has started: no progress is reported until the card's
 * wallet has reached the server, so a null here means connecting and a non-null one means scanning.
 * [fraction] stays null until the SDK measures something, which is a while into a scan.
 */
internal data class GiftCheckProgress(
    val fraction: Float?,
)

/** Why a check cannot run right now. A greyed button with no reason reads as a broken button. */
internal enum class GiftCheckBlocked {
    /**
     * No transaction id was ever recorded, so there is nothing to look for. True of a card that was
     * never funded, and of one whose broadcast outcome was never seen — money may have left for
     * that second one, which is why the copy must not claim nothing was sent.
     */
    NO_TRANSACTION,

    /** Another card is being checked, and each check is a full chain scan. */
    ANOTHER_RUNNING,
}

internal enum class GiftCardListError {
    LINK_FAILED,
    SHARE_FAILED,

    /** The card's server was unreachable, so the scan never started. */
    CHECK_UNREACHABLE,

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
    /** When this card was last confirmed to still hold its funds. Null until one check completes. */
    val lastCheckedAt: StringResource?,
    /** Whether the control is shown at all. Hidden only once a card is settled. */
    val isCheckable: Boolean,
    /** Starts a check, or stops the one running. Null renders the control disabled rather than
     *  absent, so rows do not silently differ. */
    val onCheck: (() -> Unit)?,
    /** Non-null exactly when [onCheck] is null and [isCheckable] is true. */
    val checkBlockedReason: GiftCheckBlocked?,
    val isChecking: Boolean,
    /** Null while still connecting — see [GiftCheckProgress]. */
    val checkProgress: GiftCheckProgress?,
    /** Null while the card holds nothing to hand over — see `GiftCardListVM.toItem`. */
    val onShare: ((String) -> Unit)?,
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
    val error: GiftCardListError?,
    val onBack: () -> Unit,
)
