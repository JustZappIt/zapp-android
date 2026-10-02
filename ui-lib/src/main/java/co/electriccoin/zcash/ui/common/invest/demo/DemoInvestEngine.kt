package co.electriccoin.zcash.ui.common.invest.demo

import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.invest.model.BuyEstimate
import co.electriccoin.zcash.ui.common.invest.model.BuyProgress
import co.electriccoin.zcash.ui.common.invest.model.Holding
import co.electriccoin.zcash.ui.common.invest.model.Holdings
import co.electriccoin.zcash.ui.common.invest.model.InvestAsset
import co.electriccoin.zcash.ui.common.invest.model.InvestAssets
import co.electriccoin.zcash.ui.common.invest.model.InvestMarket
import co.electriccoin.zcash.ui.common.invest.model.MarketAsset
import co.electriccoin.zcash.ui.common.invest.model.PendingTrade
import co.electriccoin.zcash.ui.common.invest.model.PreparedBuy
import co.electriccoin.zcash.ui.common.invest.model.SellProgress
import co.electriccoin.zcash.ui.common.invest.provider.InvestApiException
import co.electriccoin.zcash.ui.common.invest.repository.InvestRepository
import co.electriccoin.zcash.ui.common.invest.repository.InvestSettingsRepository
import co.electriccoin.zcash.ui.common.invest.repository.InvestSwapAssetSource
import co.electriccoin.zcash.ui.common.invest.repository.InvestTradeFollower
import co.electriccoin.zcash.ui.common.model.DynamicSwapAddress
import co.electriccoin.zcash.ui.common.model.SwapAddress
import co.electriccoin.zcash.ui.common.model.SwapAsset
import co.electriccoin.zcash.ui.common.model.SwapQuote
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import co.electriccoin.zcash.ui.common.repository.SwapRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.lang.reflect.Proxy
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

/**
 * The demo build's Invest engine (`ZAPP_INVEST_DEMO`, debug builds only): prices, quotes, buys, sales and holdings
 * are all simulated on the phone. Nothing reaches 1Click, no key is derived and no real ZEC moves, so the whole flow
 * can be walked through on an emulator. Buys and sales move [DemoWallet]'s pretend balance, which starts at 9 ZEC.
 * A trade steps through its states a few seconds apart and ends the way [InvestDemoControls] says. Holdings last
 * until the app is closed.
 *
 * The rules the screens rely on still hold: the $40 minimum, one trade per stock at a time, buying refused where
 * the saved country doesn't allow it, and a trade that needs attention stays pending until dismissed.
 */
@Suppress("TooManyFunctions")
internal class DemoInvestEngine(
    private val accountDataSource: AccountDataSource,
    private val swapRepository: SwapRepository,
    private val settings: InvestSettingsRepository,
    private val controls: InvestDemoControls,
    private val wallet: DemoWallet,
    private val clock: Clock = Clock.System,
    private val stepMillis: Long = STEP_MS,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : InvestRepository,
    InvestSwapAssetSource,
    InvestTradeFollower {
    /** One simulated trade, by its made-up deposit address. Exactly one of [buy] and [sell] is set. */
    data class Trade(
        val assetId: String,
        val buy: BuyProgress? = null,
        val sell: SellProgress? = null,
        val isDismissed: Boolean = false,
    ) {
        val isSale: Boolean get() = sell != null

        /** Still running, or stuck and not yet taken to support. */
        val isPending: Boolean
            get() {
                val isStuck = buy is BuyProgress.NeedsAttention || sell is SellProgress.NeedsAttention
                val isFinal = buy?.isFinal ?: sell?.isFinal ?: true
                return !isDismissed && (!isFinal || isStuck)
            }
    }

    private val _market = MutableStateFlow<InvestMarket?>(null)
    override val market: StateFlow<InvestMarket?> = _market.asStateFlow()

    private val _holdings = MutableStateFlow<Holdings?>(null)
    override val holdings: StateFlow<Holdings?> = _holdings.asStateFlow()

    /** Shares held, by asset ID. */
    private val units = MutableStateFlow<Map<String, BigDecimal>>(emptyMap())

    private val _trades = MutableStateFlow<Map<String, Trade>>(emptyMap())
    val trades: StateFlow<Map<String, Trade>> = _trades.asStateFlow()

    private val nextId = AtomicInteger(1)

    /** Trades still stepping, so a reset can stop them before they put shares back. */
    private val running = mutableSetOf<Job>()

    override val pendingTrades: Flow<List<PendingTrade>?> =
        _trades.map { all ->
            all
                .filterValues { it.isPending }
                .map { (address, trade) -> PendingTrade(address, trade.assetId, trade.isSale) }
        }

    override val pendingBuys: Flow<List<String>> =
        pendingTrades.map { list -> list.orEmpty().filterNot { it.isSale }.map { it.depositAddress } }

    override suspend fun refreshMarket() {
        delay(LOAD_MS)
        _market.value =
            InvestMarket(
                assets = InvestAssets.curated.map { MarketAsset(it, priceOf(it)) },
                updatedAt = clock.now(),
            )
    }

    override suspend fun refreshHoldings() {
        delay(LOAD_MS)
        publishHoldings()
    }

    override suspend fun estimateBuy(
        asset: InvestAsset,
        usdAmount: BigDecimal,
    ): BuyEstimate {
        delay(LOAD_MS)
        return when {
            usdAmount < InvestRepository.MINIMUM_USD -> {
                BuyEstimate.BelowMinimum(InvestRepository.MINIMUM_USD)
            }

            !controls.hasLiquidity.value -> {
                BuyEstimate.NoPrice
            }

            else -> {
                val quote = buyQuote(asset, usdAmount)
                val spendable = spendableZec()
                if (quote.zecIn + DemoWallet.NETWORK_FEE_ZEC > spendable) {
                    BuyEstimate.InsufficientZec(spendable)
                } else {
                    with(quote) { BuyEstimate.Priced(zecIn, unitsOut, usdOut, feesUsd, BUY_ETA_S, REFUND_FEE_ZEC) }
                }
            }
        }
    }

    override suspend fun prepareBuy(
        asset: InvestAsset,
        usdAmount: BigDecimal,
    ): PreparedBuy {
        require(usdAmount >= InvestRepository.MINIMUM_USD) { "Below the Invest minimum" }
        check(settings.get().isAvailable) { "Buying isn't available in the country of residence" }
        check(!hasTrade(asset.assetId)) { TRADE_IN_FLIGHT }
        delay(LOAD_MS)
        if (!controls.hasLiquidity.value) throw InvestApiException.NoPrice(null)
        val quote = buyQuote(asset, usdAmount)
        check(quote.zecIn + DemoWallet.NETWORK_FEE_ZEC <= spendableZec()) { "Not enough ZEC for this buy" }
        return PreparedBuy(
            asset = asset,
            zecIn = quote.zecIn,
            unitsOutExpected = quote.unitsOut,
            unitsOutMin = quote.unitsOut.multiply(MIN_OUT_SHARE).setScale(UNIT_SCALE, RoundingMode.DOWN),
            usdOut = quote.usdOut,
            feesUsd = quote.feesUsd,
            refundFeeZec = REFUND_FEE_ZEC,
            etaSeconds = BUY_ETA_S,
            expiresAt = clock.now() + PRICE_HOLD,
            quote = demoQuote("demo-buy-${nextId.getAndIncrement()}"),
        )
    }

    override suspend fun executeBuy(prepared: PreparedBuy): String {
        check(clock.now() < prepared.expiresAt) { "The price is no longer held; prepare the buy again" }
        check(settings.get().isAvailable) { "Buying isn't available in the country of residence" }
        val address = prepared.quote.depositAddress.address
        val assetId = prepared.asset.assetId
        startTrade(address, Trade(assetId, buy = BuyProgress.SendingZec(address)))
        // The ZEC leaves the wallet when the buy is sent, as it does for real.
        wallet.spend(prepared.zecIn + DemoWallet.NETWORK_FEE_ZEC)
        val outcome = controls.outcome.value
        launchTrade {
            step(address) { it.copy(buy = BuyProgress.PaymentReceived(address, incomplete = false)) }
            step(address) { it.copy(buy = BuyProgress.Buying(address)) }
            delay(stepMillis)
            val final =
                when (outcome) {
                    InvestDemoOutcome.COMPLETES -> BuyProgress.Held(address, prepared.unitsOutExpected)
                    InvestDemoOutcome.REFUNDED -> BuyProgress.Refunded(address, prepared.zecIn)
                    InvestDemoOutcome.NEEDS_ATTENTION -> BuyProgress.NeedsAttention(address, reference = address)
                }
            // Held before the final state shows, as the real engine refreshes holdings before it emits.
            if (final is BuyProgress.Held) addUnits(assetId, prepared.unitsOutExpected)
            if (final is BuyProgress.Refunded) wallet.receive(prepared.zecIn - REFUND_FEE_ZEC)
            finish(address) { it.copy(buy = final) }
        }
        return address
    }

    override suspend fun isAccountSupported(): Boolean = accountDataSource.getSelectedAccount() is ZashiAccount

    override fun observeBuy(depositAddress: String): Flow<BuyProgress> =
        _trades
            .mapNotNull { it[depositAddress]?.buy }
            .distinctUntilChanged()
            .transformWhile { progress ->
                emit(progress)
                !progress.isFinal
            }

    override suspend fun dismissBuy(depositAddress: String) = dismiss(depositAddress)

    // The demo leaves no swap records behind, so there is nothing for the activity list to resolve.
    override suspend fun investSwapAssets(): List<SwapAsset> = emptyList()

    // Demo trades move on their own, so there is nothing to follow.
    override suspend fun followPendingTrades(): Nothing = awaitCancellation()

    /** Clears holdings and trades, as on a fresh install; the country and setup stay. */
    fun reset() {
        synchronized(running) { running.toList() }.forEach { it.cancel() }
        wallet.reset()
        units.value = emptyMap()
        _trades.value = emptyMap()
        _holdings.value = null
    }

    // ----- shared with DemoInvestSellRepository -----

    /** A completed sale's ZEC arriving in the wallet. */
    fun payOut(zec: BigDecimal) = wallet.receive(zec)

    fun hasTrade(assetId: String): Boolean = _trades.value.values.any { it.assetId == assetId && it.isPending }

    fun heldUnits(assetId: String): BigDecimal = units.value[assetId] ?: BigDecimal.ZERO

    fun newAddress(prefix: String): String = "$prefix-${nextId.getAndIncrement()}"

    /** Runs a trade's steps until they finish or [reset] cancels them. */
    fun launchTrade(steps: suspend () -> Unit) {
        val job = scope.launch(start = CoroutineStart.LAZY) { steps() }
        synchronized(running) { running += job }
        job.invokeOnCompletion { synchronized(running) { running -= job } }
        job.start()
    }

    fun startTrade(
        address: String,
        trade: Trade,
    ) = _trades.update { all ->
        check(all.values.none { it.assetId == trade.assetId && it.isPending }) { TRADE_IN_FLIGHT }
        all + (address to trade)
    }

    /** Waits a step, then moves the trade on. */
    suspend fun step(
        address: String,
        change: (Trade) -> Trade,
    ) {
        delay(stepMillis)
        finish(address, change)
    }

    fun finish(
        address: String,
        change: (Trade) -> Trade,
    ) = _trades.update { all -> all[address]?.let { all + (address to change(it)) } ?: all }

    fun dismiss(address: String) =
        _trades.update { all -> all[address]?.let { all + (address to it.copy(isDismissed = true)) } ?: all }

    fun addUnits(
        assetId: String,
        delta: BigDecimal,
    ) {
        units.update { held ->
            val next = (held[assetId] ?: BigDecimal.ZERO) + delta
            if (next.signum() > 0) held + (assetId to next) else held - assetId
        }
        publishHoldings()
    }

    /** The current demo price of [asset] in USD. */
    fun priceOf(asset: InvestAsset): BigDecimal {
        val base = BASE_PRICES[asset.ticker] ?: DEFAULT_PRICE
        val wobble = BigDecimal(Random.nextDouble(-PRICE_WOBBLE, PRICE_WOBBLE))
        return base.multiply(BigDecimal.ONE + wobble).setScale(2, RoundingMode.HALF_UP)
    }

    fun zecUsd(): BigDecimal =
        swapRepository.assets.value.zecAsset
            ?.usdPrice
            ?.takeIf { it.signum() > 0 } ?: DEMO_ZEC_USD

    private suspend fun spendableZec(): BigDecimal =
        accountDataSource
            .getSelectedAccount()
            .spendableShieldedBalance.value
            .toBigDecimal()
            .movePointLeft(ZEC_SCALE)

    private fun publishHoldings() {
        val items =
            units.value.mapNotNull { (assetId, held) ->
                InvestAssets.find(assetId)?.let { asset ->
                    Holding(asset, held, held.multiply(priceOf(asset)).setScale(2, RoundingMode.HALF_UP))
                }
            }
        _holdings.value =
            Holdings(
                items = items,
                totalUsd =
                    items
                        .mapNotNull { it.usdValue }
                        .takeIf { it.isNotEmpty() }
                        ?.fold(BigDecimal.ZERO, BigDecimal::add),
                updatedAt = clock.now(),
                isStale = false,
            )
    }

    private data class BuyQuote(
        val zecIn: BigDecimal,
        val unitsOut: BigDecimal,
        val usdOut: BigDecimal,
        val feesUsd: BigDecimal,
    )

    private fun buyQuote(
        asset: InvestAsset,
        usd: BigDecimal,
    ): BuyQuote {
        val fees = usd.multiply(FEE_SHARE).setScale(2, RoundingMode.HALF_UP)
        val usdOut = usd - fees
        return BuyQuote(
            zecIn = usd.divide(zecUsd(), ZEC_SCALE, RoundingMode.UP),
            unitsOut = usdOut.divide(priceOf(asset), UNIT_SCALE, RoundingMode.DOWN),
            usdOut = usdOut,
            feesUsd = fees,
        )
    }

    // Only the deposit address of a buy's quote is ever read outside the real engine.
    private fun demoQuote(address: String): SwapQuote {
        val unsupported =
            Proxy.newProxyInstance(SwapQuote::class.java.classLoader, arrayOf(SwapQuote::class.java)) { _, method, _ ->
                error("The demo quote has no ${method.name}")
            } as SwapQuote
        return object : SwapQuote by unsupported {
            override val depositAddress: SwapAddress = DynamicSwapAddress(address)
        }
    }

    companion object {
        const val STEP_MS = 3_000L
        const val TRADE_IN_FLIGHT = "A buy or sale of this stock is still in progress"
        const val UNIT_SCALE = 6
        const val ZEC_SCALE = 8
        private const val LOAD_MS = 600L
        private const val BUY_ETA_S = 480
        private const val PRICE_WOBBLE = 0.004
        private val PRICE_HOLD = 10.minutes
        private val REFUND_FEE_ZEC = BigDecimal("0.00032")
        private val MIN_OUT_SHARE = BigDecimal("0.99")

        /** 1Click's 0.2 % plus typical price impact, as quoted on 2026-09-25. */
        val FEE_SHARE = BigDecimal("0.0095")

        /** Used only when the app has no ZEC price of its own. */
        private val DEMO_ZEC_USD = BigDecimal(50)
        private val DEFAULT_PRICE = BigDecimal(100)

        // Rounded late-September 2026 prices, so the figures look right in a demo.
        private val BASE_PRICES =
            mapOf(
                "NVDA" to BigDecimal("224.46"),
                "TSLA" to BigDecimal("438.10"),
                "SPY" to BigDecimal("663.70"),
                "QQQ" to BigDecimal("598.20"),
                "GOOGL" to BigDecimal("246.50"),
                "CRCL" to BigDecimal("131.40"),
                "AAPL" to BigDecimal("255.40"),
                "MSFT" to BigDecimal("511.20"),
                "AMZN" to BigDecimal("219.80"),
                "META" to BigDecimal("743.80"),
            )
    }
}
