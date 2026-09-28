package co.electriccoin.zcash.ui.screen.invest.section

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.invest.model.Holdings
import co.electriccoin.zcash.ui.common.invest.model.InvestEligibility
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
import co.electriccoin.zcash.ui.screen.invest.common.toInvestMessage
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
 * The PAY tab's Invest block, kept out of HomeVM. Holdings refresh once when the tab opens (the repository keeps
 * the last figures, so a failure leaves them on screen marked stale). [isInvestEnabled] is
 * `BuildConfig.IS_INVEST_ENABLED` in the app and a plain flag in tests.
 *
 * Invest is only for the phone's own account (decided 2026-09-27): with a Keystone account selected, nothing
 * Invest-related shows. [InvestRepository.isAccountSupported] decides, asked again whenever the account changes.
 */
internal class InvestmentsSectionVM(
    private val investRepository: InvestRepository,
    settingsRepository: InvestSettingsRepository,
    accountDataSource: AccountDataSource,
    currencyProvider: InvestCurrencyProvider,
    private val tradeFollower: InvestTradeFollower,
    private val navigateToInvest: NavigateToInvestUseCase,
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

    // Money shows in the user's currency, like the PAY balance; USD without an exchange rate.
    private val currency =
        currencyProvider.observe().stateIn(viewModelScope, SharingStarted.Eagerly, InvestCurrency.USD)

    private val isOwnAccount =
        accountDataSource.selectedAccount
            .map { investCatching { investRepository.isAccountSupported() }.getOrDefault(false) }
            .distinctUntilChanged()

    init {
        if (isInvestEnabled) {
            viewModelScope.launch {
                combine(
                    settingsRepository.settings.map { it.isAvailable && it.setupComplete },
                    isOwnAccount,
                ) { isSetUp, isOwnAccount -> isSetUp && isOwnAccount }
                    .distinctUntilChanged()
                    .filter { it }
                    .collect { refreshHoldings() }
            }
        }
    }

    val state: StateFlow<InvestPayState> =
        if (!isInvestEnabled) {
            MutableStateFlow(InvestPayState.HIDDEN)
        } else {
            combine(
                settingsRepository.settings,
                investRepository.holdings,
                refresh,
                isOwnAccount.distinctUntilChanged(),
                currency,
            ) { settings, holdings, status, isOwnAccount, _ ->
                if (isOwnAccount) buildState(settings, holdings, status) else InvestPayState.HIDDEN
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
                initialValue = InvestPayState.HIDDEN,
            )
        }

    private fun buildState(
        settings: InvestSettings,
        holdings: Holdings?,
        status: Refresh,
    ): InvestPayState {
        if (settings.eligibility == InvestEligibility.PROHIBITED) return InvestPayState.HIDDEN
        return InvestPayState(
            section = sectionState(settings, holdings, status),
            isSpeedDialActionVisible = true,
            onInvestClick = ::onInvestClick,
        )
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

    /**
     * Follows pending trades so a buy or sale settles without its progress screen. The PAY screen calls this while
     * it is STARTED only: this view model lives as long as the tabs do, and following polls (over Tor) all the time.
     */
    suspend fun followPendingTrades(): Nothing =
        if (isInvestEnabled) tradeFollower.followPendingTrades() else awaitCancellation()

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
