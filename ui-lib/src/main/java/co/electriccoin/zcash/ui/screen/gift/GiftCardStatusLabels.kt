// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.gift

import co.electriccoin.zcash.ui.R

/**
 * The short form, for the pill on a card face.
 *
 * Every one of these answers the only question the deck is scanned for — has it been collected —
 * and says what the sender can do about it if not. The sentences live in [labelRes].
 */
internal fun GiftCardListStatus.chipRes() =
    when (this) {
        GiftCardListStatus.UNFUNDED -> R.string.gift_card_chip_unfunded
        GiftCardListStatus.UNRESOLVED -> R.string.gift_card_chip_unresolved
        GiftCardListStatus.SUBMITTED -> R.string.gift_card_chip_submitted
        GiftCardListStatus.FUNDED -> R.string.gift_card_chip_funded
        GiftCardListStatus.SHARED -> R.string.gift_card_chip_shared
        GiftCardListStatus.CLAIMED -> R.string.gift_card_chip_claimed
    }

/** The long form, for the back of the card, where a warning has room to be a sentence. */
internal fun GiftCardListStatus.labelRes() =
    when (this) {
        GiftCardListStatus.UNFUNDED -> R.string.gift_card_list_status_unfunded
        GiftCardListStatus.UNRESOLVED -> R.string.gift_card_list_status_unresolved
        GiftCardListStatus.SUBMITTED -> R.string.gift_card_list_status_submitted
        GiftCardListStatus.FUNDED -> R.string.gift_card_list_status_funded
        GiftCardListStatus.SHARED -> R.string.gift_card_list_status_shared
        GiftCardListStatus.CLAIMED -> R.string.gift_card_list_status_claimed
    }

/** Why the check control is inert. A dead control with no reason beside it reads as a broken one. */
internal fun GiftCheckBlocked.reasonRes() =
    when (this) {
        GiftCheckBlocked.NO_TRANSACTION -> R.string.gift_card_list_check_blocked_no_tx
        GiftCheckBlocked.ANOTHER_RUNNING -> R.string.gift_card_list_check_blocked_busy
    }
