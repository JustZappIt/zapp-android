package co.electriccoin.zcash.ui.screen.invest.sellprogress

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.invest.model.InvestAssets
import co.electriccoin.zcash.ui.common.invest.model.SellProgress
import co.electriccoin.zcash.ui.common.invest.repository.InvestRepository
import co.electriccoin.zcash.ui.common.invest.repository.InvestSellRepository
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.invest.common.InvestCurrency
import co.electriccoin.zcash.ui.screen.invest.common.InvestCurrencyProvider
import co.electriccoin.zcash.ui.screen.invest.common.InvestSupport
import co.electriccoin.zcash.ui.screen.invest.common.investCatching
import co.electriccoin.zcash.ui.screen.invest.common.toInvestMessage
import co.electriccoin.zcash.ui.screen.invest.progress.InvestProgressState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * I10. Follows [InvestSellRepository.observeSell] until the sale is final. The user may leave at any point; Invest
 * home offers the way back while it is pending. It shares I7's layout.
 */
internal class InvestSellProgressVM(
    private val args: InvestSellProgressArgs,
    private val sellRepository: InvestSellRepository,
    private val investRepository: InvestRepository,
    currencyProvider: InvestCurrencyProvider,
    private val navigationRouter: NavigationRouter,
) : ViewModel() {
    private val asset = args.assetId?.let(InvestAssets::find)
    private val usdAmount = args.usdAmount?.toBigDecimalOrNull()

    private val currency =
        currencyProvider.observe().stateIn(viewModelScope, SharingStarted.Eagerly, InvestCurrency.USD)
    private val progress = MutableStateFlow<SellProgress?>(null)
    private val checkError = MutableStateFlow<StringResource?>(null)
    private var observeJob: Job? = null

    init {
        observe()
    }

    val state: StateFlow<InvestProgressState> =
        combine(progress, checkError, currency) { current, error, _ -> buildState(current, error) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
                initialValue = buildState(progress.value, checkError.value),
            )

    private fun observe() {
        if (observeJob?.isActive == true) return
        checkError.update { null }
        observeJob =
            viewModelScope.launch {
                investCatching {
                    sellRepository.observeSell(args.depositAddress).collect { next ->
                        progress.update { next }
                        if (next.isFinal) refreshHoldingsQuietly()
                    }
                }.onFailure { e ->
                    Twig.warn(e) { "InvestSellProgressVM: observing the sale failed" }
                    checkError.update { e.toInvestMessage() }
                }
            }
    }

    // The holding changed (or came back): PAY and Invest home should show it by the time the user returns.
    private suspend fun refreshHoldingsQuietly() {
        investCatching { investRepository.refreshHoldings() }
            .onFailure { Twig.warn(it) { "InvestSellProgressVM: holdings refresh after sale failed" } }
    }

    private fun buildState(
        current: SellProgress?,
        error: StringResource?,
    ): InvestProgressState {
        val attention = current as? SellProgress.NeedsAttention
        return InvestProgressState(
            title = SellProgressToSteps.title(current, asset),
            subtitle = SellProgressToSteps.subtitle(current, asset, usdAmount?.let(currency.value::format)),
            isSuccess = current is SellProgress.Sent,
            steps = SellProgressToSteps.steps(current ?: SellProgress.Authorised(args.depositAddress), asset),
            attention = attention?.let { stringRes(R.string.invest_progress_attention_body, it.reference) },
            checkError = error.takeIf { current?.isFinal != true || attention != null },
            onCheckAgain = ::observe,
            primaryButton = ButtonState(stringRes(R.string.invest_back_to_pay), onClick = navigationRouter::backToRoot),
            contactSupportButton =
                attention?.let {
                    ButtonState(stringRes(R.string.invest_contact_support)) {
                        InvestSupport.contact(navigationRouter, InvestSupport.Kind.SELL, it.reference)
                    }
                },
            removeButton =
                attention?.let { ButtonState(stringRes(R.string.invest_progress_remove), onClick = ::onRemove) },
            onBack = navigationRouter::back,
        )
    }

    // Only the list entry goes: support has the reference, and 1Click still settles the sale.
    private fun onRemove() {
        viewModelScope.launch {
            investCatching { sellRepository.dismissSell(args.depositAddress) }
                .onSuccess { navigationRouter.back() }
                .onFailure { e ->
                    Twig.warn(e) { "InvestSellProgressVM: dismissing the sale failed" }
                    checkError.update { stringRes(R.string.invest_error_generic) }
                }
        }
    }
}
