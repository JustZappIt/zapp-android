package co.electriccoin.zcash.ui.common.invest.model

/**
 * When Ondo mints and redeems an asset. It says when a price *should* exist; whether 1Click actually quotes
 * is only known by asking (on Saturday 2026-09-26 even [ALWAYS] names mostly returned "No liquidity").
 */
enum class TradingSchedule {
    /** Ondo's 24/7 names. */
    ALWAYS,

    /** Ondo's 24/5 window: Sunday 20:00 to Friday 20:00 US Eastern. */
    WEEKDAYS,
}

/**
 * One of the curated stocks Invest offers. [assetId] is the 1Click asset ID; the list is matched on it, never
 * on [ticker], so a renamed symbol can't silently point at a different token.
 */
data class InvestAsset(
    val assetId: String,
    /** The underlying ticker shown to people, without Ondo's "on" suffix. */
    val ticker: String,
    val name: String,
    val schedule: TradingSchedule,
)

/**
 * The launch list, decided 2026-09-27: six 24/7 names and four weekday names. All are Ondo tokens on BSC,
 * held inside NEAR Intents as `nep141:bnb-<contract>.omdep.near` (from `/v0/tokens?ondoTokens`, 2026-09-26).
 *
 * Only NVIDIA, Apple, Tesla and Microsoft have been quoted so far. The rest must be dry-quoted at $40 and
 * $100 on a weekday before release, and dropped if they don't quote.
 */
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
