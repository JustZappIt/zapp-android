package co.electriccoin.zcash.ui.common.invest.model

import co.electriccoin.zcash.ui.common.repository.TransactionSwapMetadata

enum class TradingSchedule {
    ALWAYS,

    /** Sunday 20:00 to Friday 20:00 US Eastern. */
    WEEKDAYS,
}

/** A curated stock, matched on its 1Click [assetId], never on [ticker]. */
data class InvestAsset(
    val assetId: String,
    /** Without Ondo's "on" suffix. */
    val ticker: String,
    val name: String,
    val schedule: TradingSchedule,
) {
    /** 1Click's token symbol, e.g. `NVDAon`, as a swap record stores it. */
    val symbol: String get() = ticker + ONDO_SUFFIX

    val chain: String get() = ONDO_CHAIN

    val decimals: Int get() = ONDO_DECIMALS

    private companion object {
        const val ONDO_SUFFIX = "on"
        const val ONDO_CHAIN = "bsc"
        const val ONDO_DECIMALS = 18
    }
}

object InvestAssets {
    val curated: List<InvestAsset> =
        listOf(
            ondo("0xa9ee28c80f960b889dfbd1902055218cba016f75", "NVDA", "NVIDIA", TradingSchedule.ALWAYS),
            ondo("0x2494b603319d4d9f9715c9f4496d9e0364b59d93", "TSLA", "Tesla", TradingSchedule.ALWAYS),
            ondo("0x6a708ead771238919d85930b5a0f10454e1c331a", "SPY", "S&P 500 ETF", TradingSchedule.ALWAYS),
            ondo("0x0cde6936d305d5b34667fc46425e852efd73559a", "QQQ", "Nasdaq 100 ETF", TradingSchedule.ALWAYS),
            ondo("0x091fc7778e6932d4009b087b191d1ee3bac5729a", "GOOGL", "Alphabet", TradingSchedule.ALWAYS),
            ondo("0x992879cd8ce0c312d98648875b5a8d6d042cbf34", "CRCL", "Circle", TradingSchedule.ALWAYS),
            ondo("0x390a684ef9cade28a7ad0dfa61ab1eb3842618c4", "AAPL", "Apple", TradingSchedule.WEEKDAYS),
            ondo("0x6bfe75d1ad432050ea973c3a3dcd88f02e2444c3", "MSFT", "Microsoft", TradingSchedule.WEEKDAYS),
            ondo("0x4553cfe1c09f37f38b12dc509f676964e392f8fc", "AMZN", "Amazon", TradingSchedule.WEEKDAYS),
            ondo("0xd7df5863a3e742f0c767768cdfcb63f09e0422f6", "META", "Meta", TradingSchedule.WEEKDAYS),
        )

    private val byAssetId = curated.associateBy { it.assetId }

    fun find(assetId: String): InvestAsset? = byAssetId[assetId]

    /** The curated stock with these swap-record tickers; ordinary Swap never offers one. */
    fun findBySwapTickers(
        tokenTicker: String,
        chainTicker: String,
    ): InvestAsset? =
        curated.firstOrNull {
            it.symbol.equals(tokenTicker, ignoreCase = true) && it.chain.equals(chainTicker, ignoreCase = true)
        }

    private fun ondo(
        bscContract: String,
        ticker: String,
        name: String,
        schedule: TradingSchedule,
    ) = InvestAsset(
        assetId = "nep141:bnb-$bscContract.omdep.near",
        ticker = ticker,
        name = name,
        schedule = schedule,
    )
}

/** The stock an Invest buy's swap record bought, or null for any other swap. */
fun TransactionSwapMetadata.investAsset(): InvestAsset? =
    InvestAssets.findBySwapTickers(destination.tokenTicker, destination.chainTicker)
