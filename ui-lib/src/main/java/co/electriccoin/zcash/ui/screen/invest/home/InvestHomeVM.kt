package co.electriccoin.zcash.ui.screen.invest.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.invest.model.Holdings
import co.electriccoin.zcash.ui.common.invest.model.InvestAsset
import co.electriccoin.zcash.ui.common.invest.model.InvestAssets
import co.electriccoin.zcash.ui.common.invest.model.InvestMarket
import co.electriccoin.zcash.ui.common.invest.model.PendingTrade
import co.electriccoin.zcash.ui.common.invest.model.TradingSchedule
import co.electriccoin.zcash.ui.common.invest.repository.InvestRepository
import co.electriccoin.zcash.ui.common.invest.repository.InvestTradeFollower
import co.electriccoin.zcash.ui.common.usecase.IsTorEnabledUseCase
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.invest.buy.InvestBuyArgs
import co.electriccoin.zcash.ui.screen.invest.common.InvestCurrency
import co.electriccoin.zcash.ui.screen.invest.common.InvestCurrencyProvider
import co.electriccoin.zcash.ui.screen.invest.common.InvestFormat
import co.electriccoin.zcash.ui.screen.invest.common.InvestSession
import co.electriccoin.zcash.ui.screen.invest.common.UsMarketHours
import co.electriccoin.zcash.ui.screen.invest.common.investCatching
import co.electriccoin.zcash.ui.screen.invest.common.progressRoute
import co.electriccoin.zcash.ui.screen.invest.common.toInvestMessage
import co.electriccoin.zcash.ui.screen.invest.common.unreadableRecordsState
import co.electriccoin.zcash.ui.screen.invest.section.HoldingTrade
import co.electriccoin.zcash.ui.screen.invest.section.InvestHoldingRowState
import co.electriccoin.zcash.ui.screen.invest.sell.InvestSellArgs
import co.electriccoin.zcash.ui.screen.tor.settings.TorSettingsArgs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import kotlin.time.Clock
import kotlin.time.toJavaInstant

/**
 * I3: the holdings summary, the curated stocks in their two groups, and the two banners (Tor off, US market
 * closed). Tapping a stock opens the buy amount screen; there is no search or stock detail in this version.
 */
@Suppress("TooManyFunctions")
internal class InvestHomeVM(
    private val investRepository: InvestRepository,
    isTorEnabled: IsTorEnabledUseCase,
    currencyProvider: InvestCurrencyProvider,
    tradeFollower: InvestTradeFollower,
    private val session: InvestSession,
    private val navigationRouter: NavigationRouter,
    private val clock: Clock,
) : ViewModel() {
    private data class Status(
        val marketError: StringResource? = null,
        val holdingsFailed: Boolean = false,
    )

    private val status = MutableStateFlow(Status())

    // Money shows in the user's currency, like the PAY balance; USD without an exchange rate.
    private val currency =
        currencyProvider.observe().stateIn(viewModelScope, SharingStarted.Eagerly, InvestCurrency.USD)

    // The last price seen per asset this session, so a stock whose quote has dried up can still say what it was.
    private val lastPrices = mutableMapOf<String, BigDecimal>()

    init {
        viewModelScope.launch { refreshMarket() }
        viewModelScope.launch { refreshHoldings() }
        // Settles pending trades (and unlocks their stocks) while Invest is on screen, progress screen or not.
        viewModelScope.launch { tradeFollower.followPendingTrades() }
    }

    val state: StateFlow<InvestHomeState> =
        combine(
            investRepository.market,
            investRepository.holdings,
            investRepository.pendingTrades,
            combine(isTorEnabled.observe(), session.isTorBannerDismissed) { torOn, dismissed -> torOn || dismissed },
            combine(status, currency) { current, _ -> current },
        ) { market, holdings, trades, hideTorBanner, current ->
            buildState(market, holdings, trades, hideTorBanner, current)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue = buildState(null, null, emptyList(), hideTorBanner = true, current = Status()),
        )

    private fun buildState(
        market: InvestMarket?,
        holdings: Holdings?,
        trades: List<PendingTrade>?,
        hideTorBanner: Boolean,
        current: Status,
    ): InvestHomeState {
        val trading = trades.orEmpty().associateBy { it.assetId }
        market?.assets?.forEach { asset -> asset.usdPrice?.let { lastPrices[asset.asset.assetId] = it } }
        val now = clock.now().toJavaInstant()
        return InvestHomeState(
            summary =
                holdings?.let {
                    summaryOf(it, isStale = it.isStale || current.holdingsFailed, trading, canSell = trades != null)
                },
            torBanner =
                if (hideTorBanner) {
                    null
                } else {
                    InvestTorBannerState(onTurnOn = ::onTurnOnTor, onDismiss = ::onDismissTorBanner)
                },
            marketBanner =
                if (UsMarketHours.isRegularSession(now)) {
                    null
                } else {
                    val reopens = InvestFormat.localDayTime(UsMarketHours.nextRegularOpen(now))
                    stringRes(R.string.invest_home_market_banner, reopens)
                },
            pendingTrades = trades.orEmpty().map(::pendingRow),
            recordsUnreadable = if (trades == null) unreadableRecordsState(navigationRouter) else null,
            groups = groupsOf(market, weekdaysOpen = UsMarketHours.isWeekdayWindowOpen(now)),
            marketError = current.marketError.takeIf { market == null },
            onRetryMarket = ::onRetryMarket,
            onBack = navigationRouter::back,
        )
    }

    private fun summaryOf(
        holdings: Holdings,
        isStale: Boolean,
        trading: Map<String, PendingTrade>,
        /** False when the trade records can't be read: then no stock can be sold. */
        canSell: Boolean,
    ) = InvestHomeSummary(
        total = holdings.totalUsd?.let { stringRes(currency.value.format(it)) },
        rows =
            holdings.items.map { holding ->
                InvestHoldingRowState(
                    key = holding.asset.assetId,
                    monogram = InvestFormat.monogram(holding.asset.ticker),
                    name = holding.asset.name,
                    ticker = holding.asset.ticker,
                    value = holding.usdValue?.let { stringRes(currency.value.format(it)) },
                    units = stringRes(InvestFormat.units(holding.units, holding.asset.ticker)),
                    onClick = { onStockClick(holding.asset) },
                    // A stock can't be bought and sold at once: while a trade of it runs, the holding says so instead.
                    tradeInProgress =
                        trading[holding.asset.assetId]?.let { trade ->
                            HoldingTrade(trade.isSale) { navigationRouter.forward(trade.progressRoute()) }
                        },
                    onSell = { onSellClick(holding.asset) }.takeIf { canSell && holding.asset.assetId !in trading },
                )
            },
        updatedAtEpochMillis = holdings.updatedAt.toEpochMilliseconds(),
        isStale = isStale,
        onRetry = { viewModelScope.launch { refreshHoldings() } },
    )

    private fun groupsOf(
        market: InvestMarket?,
        weekdaysOpen: Boolean,
    ): List<InvestStockGroupState> {
        // The repository already drops curated assets 1Click no longer lists; before the first load, show all.
        val prices = market?.assets?.associate { it.asset.assetId to it.usdPrice }
        val assets = market?.assets?.map { it.asset } ?: InvestAssets.curated
        val rows = assets.map { stockRow(it, prices?.get(it.assetId), isLoaded = market != null) }
        return listOf(
            InvestStockGroupState(
                title = stringRes(R.string.invest_home_group_always),
                status = null,
                rows = rows.filterIndexed { i, _ -> assets[i].schedule == TradingSchedule.ALWAYS },
            ),
            InvestStockGroupState(
                title = stringRes(R.string.invest_home_group_weekdays),
                status = if (weekdaysOpen) null else stringRes(R.string.invest_home_closed_chip),
                rows = rows.filterIndexed { i, _ -> assets[i].schedule == TradingSchedule.WEEKDAYS },
            ),
        ).filter { it.rows.isNotEmpty() }
    }

    private fun stockRow(
        asset: InvestAsset,
        price: BigDecimal?,
        isLoaded: Boolean,
    ): InvestStockRowState {
        val last = lastPrices[asset.assetId]
        return InvestStockRowState(
            key = asset.assetId,
            monogram = InvestFormat.monogram(asset.ticker),
            name = asset.name,
            ticker = asset.ticker,
            price = (price ?: last)?.let { stringRes(currency.value.format(it)) },
            caption =
                when {
                    price != null -> stringRes(R.string.invest_home_per_share)
                    !isLoaded -> null
                    last != null -> stringRes(R.string.invest_home_last_price)
                    else -> stringRes(R.string.invest_no_price_short)
                },
            isPriced = price != null,
            onClick = { onStockClick(asset) },
        )
    }

    private suspend fun refreshMarket() {
        investCatching { investRepository.refreshMarket() }
            .onSuccess { status.update { it.copy(marketError = null) } }
            .onFailure { e ->
                Twig.warn(e) { "InvestHomeVM: market refresh failed" }
                status.update { it.copy(marketError = e.toInvestMessage()) }
            }
    }

    private suspend fun refreshHoldings() {
        investCatching { investRepository.refreshHoldings() }
            .onSuccess { status.update { it.copy(holdingsFailed = false) } }
            .onFailure { e ->
                Twig.warn(e) { "InvestHomeVM: holdings refresh failed" }
                status.update { it.copy(holdingsFailed = true) }
            }
    }

    private fun onRetryMarket() {
        viewModelScope.launch { refreshMarket() }
    }

    private fun onTurnOnTor() = navigationRouter.forward(TorSettingsArgs)

    private fun onDismissTorBanner() = session.isTorBannerDismissed.update { true }

    private fun onStockClick(asset: InvestAsset) = navigationRouter.forward(InvestBuyArgs(assetId = asset.assetId))

    private fun onSellClick(asset: InvestAsset) = navigationRouter.forward(InvestSellArgs(assetId = asset.assetId))

    // Each row names its stock: the trades come with it.
    private fun pendingRow(trade: PendingTrade): InvestPendingTradeRow {
        val name = InvestAssets.find(trade.assetId)?.name
        val title =
            when {
                name == null -> stringRes(R.string.invest_home_pending_sale_title_unknown)
                trade.isSale -> stringRes(R.string.invest_home_pending_sale_title, name)
                else -> stringRes(R.string.invest_home_pending_buy_title, name)
            }
        return InvestPendingTradeRow(trade.depositAddress, title) { navigationRouter.forward(trade.progressRoute()) }
    }
}
