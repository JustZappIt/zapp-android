// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.gift

import co.electriccoin.zcash.ui.design.theme.colors.ZappGiftCardStock
import co.electriccoin.zcash.ui.design.theme.colors.ZappGiftCardStocks

/** ISO 7810 ID-1. A card that is not this shape reads as a panel calling itself a card. */
internal const val GIFT_CARD_ASPECT = 1.586f

private const val ZATOSHI_PER_ZEC = 100_000_000L

/**
 * Where a denomination sits on the ladder.
 *
 * A gift card has no recipient to name it by, so a deck needs something else to tell four cards
 * apart at a glance — and a sender filling in an amount deserves to see the gift get better as the
 * figure climbs. Denomination is the one thing every card has, so it decides both.
 */
internal enum class GiftCardTier {
    /** Under 0.1 ZEC. */
    PAPER,

    /** 0.1 to 0.5 ZEC. */
    GRAPHITE,

    /** 0.5 to 2 ZEC. */
    OBSIDIAN,

    /** 2 to 10 ZEC. */
    AMBER,

    /** 10 ZEC and up. */
    SIGNATURE,

    /** Collected. Overrides denomination: a spent card is a receipt, not a gift. */
    SPENT,
}

/** 0.1 ZEC. */
private const val PAPER_CEILING = ZATOSHI_PER_ZEC / 10

/** 0.5 ZEC. */
private const val GRAPHITE_CEILING = ZATOSHI_PER_ZEC / 2

/** 2 ZEC. */
private const val OBSIDIAN_CEILING = ZATOSHI_PER_ZEC * 2

/** 10 ZEC. Above this a card is a Signature, and there is no rung after it. */
private const val AMBER_CEILING = ZATOSHI_PER_ZEC * 10

private val LADDER =
    listOf(
        PAPER_CEILING to GiftCardTier.PAPER,
        GRAPHITE_CEILING to GiftCardTier.GRAPHITE,
        OBSIDIAN_CEILING to GiftCardTier.OBSIDIAN,
        AMBER_CEILING to GiftCardTier.AMBER,
    )

/**
 * Shared by the sender's deck, the create flow's live preview and the recipient's claim screen, so
 * a card that was handed over as amber is the same amber card when it arrives.
 */
internal fun giftCardTier(amountZatoshi: Long, isSettled: Boolean): GiftCardTier =
    when {
        isSettled -> GiftCardTier.SPENT
        else -> LADDER.firstOrNull { amountZatoshi < it.first }?.second ?: GiftCardTier.SIGNATURE
    }

internal fun GiftCardTier.stock(): ZappGiftCardStock =
    when (this) {
        GiftCardTier.PAPER -> ZappGiftCardStocks.Paper
        GiftCardTier.GRAPHITE -> ZappGiftCardStocks.Graphite
        GiftCardTier.OBSIDIAN -> ZappGiftCardStocks.Obsidian
        GiftCardTier.AMBER -> ZappGiftCardStocks.Amber
        GiftCardTier.SIGNATURE -> ZappGiftCardStocks.Signature
        GiftCardTier.SPENT -> ZappGiftCardStocks.Spent
    }
