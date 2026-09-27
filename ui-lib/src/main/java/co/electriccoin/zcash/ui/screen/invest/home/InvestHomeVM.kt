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
import co.electriccoin.zcash.ui.common.invest.model.TradingSchedule
import co.electriccoin.zcash.ui.common.invest.repository.InvestRepository
import co.electriccoin.zcash.ui.common.usecase.IsTorEnabledUseCase
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.invest.buy.InvestBuyArgs
import co.electriccoin.zcash.ui.screen.invest.common.InvestFormat
import co.electriccoin.zcash.ui.screen.invest.common.UsMarketHours
import co.electriccoin.zcash.ui.screen.invest.common.investCatching
import co.electriccoin.zcash.ui.screen.invest.common.toInvestMessage
import co.electriccoin.zcash.ui.screen.invest.progress.InvestProgressArgs
import co.electriccoin.zcash.ui.screen.invest.section.InvestHoldingRowState
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
    private val navigationRouter: NavigationRouter,
    private val clock: Clock,
) : ViewModel() {
    private data class Status(
        val isTorBannerDismissed: Boolean = false,
        val marketError: StringResource? = null,
        val holdingsFailed: Boolean = false,
    )

    private val status = MutableStateFlow(Status())

    // The last price seen per asset this session, so a stock whose quote has dried up can still say what it was.
    private val lastPrices = mutableMapOf<String, BigDecimal>()

    init {
        viewModelScope.launch { refreshMarket() }
        viewModelScope.launch { refreshHoldings() }
    }

    val state: StateFlow<InvestHomeState> =
        combine(
            investRepository.market,
            investRepository.holdings,
            investRepository.pendingBuys,
            isTorEnabled.observe(),
            status,
        ) { market, holdings, pending, torOn, current ->
            buildState(market, holdings, pending, torOn, current)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue = buildState(null, null, emptyList(), torOn = true, current = Status()),
        )

    private fun buildState(
        market: InvestMarket?,
        holdings: Holdings?,
        pending: List<String>,
        torOn: Boolean,
        current: Status,
    ): InvestHomeState {
        market?.assets?.forEach { asset -> asset.usdPrice?.let { lastPrices[asset.asset.assetId] = it } }
        val now = clock.now().toJavaInstant()
        return InvestHomeState(
            summary = holdings?.let { summaryOf(it, isStale = it.isStale || current.holdingsFailed) },
            torBanner =
                if (torOn || current.isTorBannerDismissed) {
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
            pendingBuys = pending.map { InvestPendingBuyRow(it) { onPendingBuyClick(it) } },
            groups = groupsOf(market, weekdaysOpen = UsMarketHours.isWeekdayWindowOpen(now)),
            marketError = current.marketError.takeIf { market == null },
            onRetryMarket = ::onRetryMarket,
            onBack = navigationRouter::back,
        )
    }

    private fun summaryOf(
        holdings: Holdings,
        isStale: Boolean,
    ) = InvestHomeSummary(
        total = holdings.totalUsd?.let { stringRes(InvestFormat.usd(it)) },
        rows =
            holdings.items.map { holding ->
                InvestHoldingRowState(
                    key = holding.asset.assetId,
                    monogram = InvestFormat.monogram(holding.asset.ticker),
                    name = holding.asset.name,
                    ticker = holding.asset.ticker,
                    value = holding.usdValue?.let { stringRes(InvestFormat.usd(it)) },
                    units = stringRes(InvestFormat.units(holding.units, holding.asset.ticker)),
                    onClick = { onStockClick(holding.asset) },
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
            price = (price ?: last)?.let { stringRes(InvestFormat.usd(it)) },
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

    private fun onDismissTorBanner() = status.update { it.copy(isTorBannerDismissed = true) }

    private fun onPendingBuyClick(depositAddress: String) =
        navigationRouter.forward(InvestProgressArgs(depositAddress = depositAddress))

    private fun onStockClick(asset: InvestAsset) = navigationRouter.forward(InvestBuyArgs(assetId = asset.assetId))
}
