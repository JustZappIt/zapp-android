// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.gift

import co.electriccoin.zcash.ui.design.theme.colors.ZappGiftCardStock
import co.electriccoin.zcash.ui.design.theme.colors.ZappGiftCardStocks

/** ISO 7810 ID-1. A card that is not this shape reads as a panel calling itself a card. */
internal const val GIFT_CARD_ASPECT = 1.586f

/** 1 ZEC. */
private const val AMBER_FROM_ZATOSHI = 100_000_000L

/** 0.1 ZEC. */
private const val OBSIDIAN_FROM_ZATOSHI = 10_000_000L

/**
 * The stock a gift card is printed on, chosen by denomination.
 *
 * A gift card has no recipient to name it by, so a deck needs something else to tell four cards
 * apart at a glance. Denomination is the one thing every card has, and it is also what the sender
 * is actually looking for.
 */
internal enum class GiftCardTier {
    BONE,
    OBSIDIAN,
    AMBER,

    /** Collected. Overrides denomination: a spent card is a receipt, not a gift. */
    SPENT,
}

/**
 * Shared by the sender's deck and the recipient's claim screen, so a card that was handed over as
 * amber is the same amber card when it arrives.
 */
internal fun giftCardTier(amountZatoshi: Long, isSettled: Boolean): GiftCardTier =
    when {
        isSettled -> GiftCardTier.SPENT
        amountZatoshi >= AMBER_FROM_ZATOSHI -> GiftCardTier.AMBER
        amountZatoshi >= OBSIDIAN_FROM_ZATOSHI -> GiftCardTier.OBSIDIAN
        else -> GiftCardTier.BONE
    }

internal fun GiftCardTier.stock(): ZappGiftCardStock =
    when (this) {
        GiftCardTier.BONE -> ZappGiftCardStocks.Bone
        GiftCardTier.OBSIDIAN -> ZappGiftCardStocks.Obsidian
        GiftCardTier.AMBER -> ZappGiftCardStocks.Amber
        GiftCardTier.SPENT -> ZappGiftCardStocks.Spent
    }
