// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.gift

import cash.z.ecc.android.sdk.ext.convertZatoshiToZec
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.design.theme.colors.ZappGiftCardStock
import co.electriccoin.zcash.ui.design.theme.colors.ZappGiftCardStocks
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.TickerLocation
import co.electriccoin.zcash.ui.design.util.stringResByCurrencyNumber
import co.electriccoin.zcash.ui.util.CURRENCY_TICKER

/** ISO 7810 ID-1. A card that is not this shape reads as a panel calling itself a card. */
internal const val GIFT_CARD_ASPECT = 1.586f

private const val ZATOSHI_PER_ZEC = 100_000_000L

/**
 * Where a denomination sits on the ladder.
 *
 * A gift card has no recipient to name it by, so a deck needs something else to tell cards
 * apart at a glance — and a sender filling in an amount deserves to see the gift get better as the
 * figure climbs. Denomination is the one thing every card has, so it decides both.
 */
internal enum class GiftCardTier {
    /** Under 0.1 ZEC. */
    CLAY,

    /** 0.1 to 0.25 ZEC. */
    SLATE,

    /** 0.25 to 0.5 ZEC. The first red. */
    CINNABAR,

    /** 0.5 to 1 ZEC. */
    VERMILION,

    /** 1 to 2 ZEC. */
    COPPER,

    /** 2 to 5 ZEC. */
    AMBER,

    /** 5 to 10 ZEC. The black card. */
    TIGER,

    /** 10 to 50 ZEC. */
    SIGNATURE,

    /** 50 ZEC and up. */
    DRAGON,

    /** Collected. Overrides denomination: a spent card is a receipt, not a gift. */
    SPENT,
}

// The boundaries have not moved since the ladder was first drawn - only the stock printed on each
// side of them has. A card made before a restyle lands on the same rung it always did, wearing
// whatever that rung looks like now.

/** 0.1 ZEC. */
private const val CLAY_CEILING = ZATOSHI_PER_ZEC / 10

/** 0.25 ZEC. */
private const val SLATE_CEILING = ZATOSHI_PER_ZEC / 4

/** 0.5 ZEC. */
private const val CINNABAR_CEILING = ZATOSHI_PER_ZEC / 2

/** 1 ZEC. */
private const val VERMILION_CEILING = ZATOSHI_PER_ZEC

/** 2 ZEC. */
private const val COPPER_CEILING = ZATOSHI_PER_ZEC * 2

/** 5 ZEC. */
private const val AMBER_CEILING = ZATOSHI_PER_ZEC * 5

/** 10 ZEC. */
private const val TIGER_CEILING = ZATOSHI_PER_ZEC * 10

/** 50 ZEC. Above this a card is a Dragon, and there is no rung after it. */
private const val SIGNATURE_CEILING = ZATOSHI_PER_ZEC * 50

private val LADDER =
    listOf(
        CLAY_CEILING to GiftCardTier.CLAY,
        SLATE_CEILING to GiftCardTier.SLATE,
        CINNABAR_CEILING to GiftCardTier.CINNABAR,
        VERMILION_CEILING to GiftCardTier.VERMILION,
        COPPER_CEILING to GiftCardTier.COPPER,
        AMBER_CEILING to GiftCardTier.AMBER,
        TIGER_CEILING to GiftCardTier.TIGER,
        SIGNATURE_CEILING to GiftCardTier.SIGNATURE,
    )

/**
 * Shared by the sender's deck, the create flow's live preview and the recipient's claim screen, so
 * a card that was handed over as amber is the same amber card when it arrives.
 */
internal fun giftCardTier(amountZatoshi: Long, isSettled: Boolean): GiftCardTier =
    when {
        isSettled -> GiftCardTier.SPENT
        else -> LADDER.firstOrNull { amountZatoshi < it.first }?.second ?: GiftCardTier.DRAGON
    }

internal fun GiftCardTier.stock(): ZappGiftCardStock =
    when (this) {
        GiftCardTier.CLAY -> ZappGiftCardStocks.Clay
        GiftCardTier.SLATE -> ZappGiftCardStocks.Slate
        GiftCardTier.CINNABAR -> ZappGiftCardStocks.Cinnabar
        GiftCardTier.VERMILION -> ZappGiftCardStocks.Vermilion
        GiftCardTier.COPPER -> ZappGiftCardStocks.Copper
        GiftCardTier.AMBER -> ZappGiftCardStocks.Amber
        GiftCardTier.TIGER -> ZappGiftCardStocks.Tiger
        GiftCardTier.SIGNATURE -> ZappGiftCardStocks.Signature
        GiftCardTier.DRAGON -> ZappGiftCardStocks.Dragon
        GiftCardTier.SPENT -> ZappGiftCardStocks.Spent
    }

/** Zatoshi resolve exactly at eight decimals, so nothing is rounded away before the zeros are. */
private const val GIFT_AMOUNT_SCALE = 8

/**
 * How a card prints its denomination.
 *
 * The wallet's default Zatoshi formatting pads to three decimals so figures line up down a column,
 * which is right in a transaction list and wrong on a card: this figure is a hero with nothing to
 * align to, and `10.000 ZEC` claims a precision the gift has not got. Trailing zeros are dropped
 * here, so a round gift prints round and a fractional one still shows every place it needs.
 */
internal fun giftAmountRes(zatoshi: Zatoshi): StringResource =
    stringResByCurrencyNumber(
        amount = zatoshi.convertZatoshiToZec(GIFT_AMOUNT_SCALE),
        ticker = CURRENCY_TICKER,
        tickerLocation = TickerLocation.AFTER,
        minDecimals = 0,
        maxDecimals = GIFT_AMOUNT_SCALE,
    )
