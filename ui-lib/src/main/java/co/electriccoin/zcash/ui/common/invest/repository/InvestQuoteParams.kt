package co.electriccoin.zcash.ui.common.invest.repository

import kotlin.time.Duration.Companion.minutes

/** What both Invest engines quote with. */
internal object InvestQuoteParams {
    const val ZEC_ASSET_ID = "nep141:zec.omft.near"
    const val ZEC_DECIMALS = 8

    /** 1 %. */
    const val SLIPPAGE_BPS = 100
    const val QUOTE_WAITING_TIME_MS = 3_000
    const val REFERRAL = "zapp"

    /** How long a review sheet holds one price. */
    val PRICE_HELD = 10.minutes

    /** How old a price may be when it sizes a trade. */
    val PRICE_MAX_AGE = 1.minutes
}
