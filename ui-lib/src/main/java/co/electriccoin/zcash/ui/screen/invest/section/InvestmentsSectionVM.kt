package co.electriccoin.zcash.ui.screen.invest.section

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.invest.model.Holdings
import co.electriccoin.zcash.ui.common.invest.model.InvestEligibility
import co.electriccoin.zcash.ui.common.invest.model.PendingTrade
import co.electriccoin.zcash.ui.common.invest.repository.InvestRepository
import co.electriccoin.zcash.ui.common.invest.repository.InvestSettings
import co.electriccoin.zcash.ui.common.invest.repository.InvestSettingsRepository
import co.electriccoin.zcash.ui.common.invest.repository.InvestTradeFollower
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.invest.NavigateToInvestUseCase
import co.electriccoin.zcash.ui.screen.invest.common.InvestCurrency
import co.electriccoin.zcash.ui.screen.invest.common.InvestCurrencyProvider
import co.electriccoin.zcash.ui.screen.invest.common.InvestFormat
import co.electriccoin.zcash.ui.screen.invest.common.investCatching
import co.electriccoin.zcash.ui.screen.invest.common.isSellOnly
import co.electriccoin.zcash.ui.screen.invest.common.toInvestMessage
import co.electriccoin.zcash.ui.screen.invest.settings.InvestSettingsArgs
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The PAY tab's Invest block and the Settings tab's Invest row, shown only for the phone's own account. Holdings
 * refresh once PAY is first shown, since a refresh is a sign-in and a balance read.
 */
internal class InvestmentsSectionVM(
    private val investRepository: InvestRepository,
    settingsRepository: InvestSettingsRepository,
    accountDataSource: AccountDataSource,
    currencyProvider: InvestCurrencyProvider,
    private val tradeFollower: InvestTradeFollower,
    private val navigateToInvest: NavigateToInvestUseCase,
    private val navigationRouter: NavigationRouter,
    private val isInvestEnabled: Boolean,
) : ViewModel() {
    private sealed interface Refresh {
        data object Idle : Refresh

        data object Loading : Refresh

        data class Failed(
            val message: StringResource,
        ) : Refresh
    }

    private val refresh = MutableStateFlow<Refresh>(Refresh.Idle)

    private val currency =
        currencyProvider.observe().stateIn(viewModelScope, SharingStarted.Eagerly, InvestCurrency.USD)

    private val isOwnAccount: StateFlow<Boolean> =
        accountDataSource.selectedAccount
            .map { investCatching { investRepository.isAccountSupported() }.getOrDefault(false) }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT), false)

    private val isSetUp = settingsRepository.settings.map { it.setupComplete }
    private var isRefreshStarted = false

    val state: StateFlow<InvestPayState> =
        if (!isInvestEnabled) {
            MutableStateFlow(InvestPayState.HIDDEN)
        } else {
            combine(
                combine(settingsRepository.settings, investRepository.pendingTrades, ::Pair),
                investRepository.holdings,
                refresh,
                isOwnAccount,
                currency,
            ) { (settings, trades), holdings, status, isOwnAccount, _ ->
                if (isOwnAccount) buildState(settings, holdings, trades, status) else InvestPayState.HIDDEN
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
                initialValue = InvestPayState.HIDDEN,
            )
        }

    val isSettingsRowVisible: StateFlow<Boolean> = if (!isInvestEnabled) MutableStateFlow(false) else isOwnAccount

    fun onSettingsClick() = navigationRouter.forward(InvestSettingsArgs)

    private fun buildState(
        settings: InvestSettings,
        holdings: Holdings?,
        trades: List<PendingTrade>?,
        status: Refresh,
    ): InvestPayState {
        val section =
            when {
                settings.isSellOnly -> sellOnlySectionState(holdings, trades, status)
                settings.eligibility == InvestEligibility.PROHIBITED -> InvestmentsSectionState.Hidden
                else -> sectionState(settings, holdings, status)
            }
        if (section == InvestmentsSectionState.Hidden) return InvestPayState.HIDDEN
        return InvestPayState(
            section = section,
            isSpeedDialActionVisible = true,
            onInvestClick = ::onInvestClick,
        )
    }

    // No entry card, as it invites a first buy.
    private fun sellOnlySectionState(
        holdings: Holdings?,
        trades: List<PendingTrade>?,
        status: Refresh,
    ): InvestmentsSectionState {
        val hasPending = !trades.isNullOrEmpty()
        return when {
            holdings == null && status is Refresh.Failed -> {
                InvestmentsSectionState.Error(status.message, ::onRetry)
            }

            holdings == null -> {
                InvestmentsSectionState.Loading(::onInvestClick)
            }

            holdings.items.isNotEmpty() || hasPending -> {
                holdingsState(holdings, isStale = holdings.isStale || status is Refresh.Failed)
            }

            else -> {
                InvestmentsSectionState.Hidden
            }
        }
    }

    private fun sectionState(
        settings: InvestSettings,
        holdings: Holdings?,
        status: Refresh,
    ): InvestmentsSectionState =
        when {
            !settings.isAvailable || !settings.setupComplete -> {
                InvestmentsSectionState.Entry(::onInvestClick)
            }

            holdings == null -> {
                if (status is Refresh.Failed) {
                    InvestmentsSectionState.Error(status.message, ::onRetry)
                } else {
                    InvestmentsSectionState.Loading(::onInvestClick)
                }
            }

            holdings.items.isEmpty() -> {
                InvestmentsSectionState.Entry(::onInvestClick)
            }

            else -> {
                holdingsState(holdings, isStale = holdings.isStale || status is Refresh.Failed)
            }
        }

    private fun holdingsState(
        holdings: Holdings,
        isStale: Boolean,
    ) = InvestmentsSectionState.Holdings(
        rows =
            holdings.items.map { holding ->
                InvestHoldingRowState(
                    key = holding.asset.assetId,
                    monogram = InvestFormat.monogram(holding.asset.ticker),
                    name = holding.asset.name,
                    ticker = holding.asset.ticker,
                    value = holding.usdValue?.let { stringRes(currency.value.format(it)) },
                    units = stringRes(InvestFormat.units(holding.units, holding.asset.ticker)),
                    onClick = ::onInvestClick,
                )
            },
        total = holdings.totalUsd?.let { stringRes(currency.value.format(it)) },
        updatedAtEpochMillis = holdings.updatedAt.toEpochMilliseconds(),
        isStale = isStale,
        onHeaderClick = ::onInvestClick,
        onRetry = ::onRetry,
    )

    /** Called while PAY is on screen; the first call also starts the holdings refresh. */
    suspend fun followPendingTrades(): Nothing {
        if (!isInvestEnabled) awaitCancellation()
        startRefreshing()
        tradeFollower.followPendingTrades()
    }

    private fun startRefreshing() {
        if (isRefreshStarted) return
        isRefreshStarted = true
        viewModelScope.launch {
            combine(isSetUp, isOwnAccount) { isSetUp, isOwnAccount -> isSetUp && isOwnAccount }
                .distinctUntilChanged()
                .filter { it }
                .collect { refreshHoldings() }
        }
    }

    private fun onInvestClick() {
        viewModelScope.launch { navigateToInvest() }
    }

    private fun onRetry() {
        viewModelScope.launch { refreshHoldings() }
    }

    private suspend fun refreshHoldings() {
        if (refresh.value is Refresh.Loading) return
        refresh.update { Refresh.Loading }
        investCatching { investRepository.refreshHoldings() }
            .onSuccess { refresh.update { Refresh.Idle } }
            .onFailure { e ->
                Twig.warn(e) { "InvestmentsSectionVM: holdings refresh failed" }
                refresh.update { Refresh.Failed(e.toInvestMessage()) }
            }
    }
}
