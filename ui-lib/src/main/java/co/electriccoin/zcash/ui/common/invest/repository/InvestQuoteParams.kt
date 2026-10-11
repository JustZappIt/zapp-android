package co.electriccoin.zcash.ui.common.invest.repository

/** The 1Click quote parameters both Invest engines send, and the ZEC side they share. */
internal object InvestQuoteParams {
    const val ZEC_ASSET_ID = "nep141:zec.omft.near"
    const val ZEC_DECIMALS = 8

    /** 1 %, for buys and sales alike. */
    const val SLIPPAGE_BPS = 100
    const val QUOTE_WAITING_TIME_MS = 3_000
    const val REFERRAL = "zapp"
}
