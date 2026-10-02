package co.electriccoin.zcash.ui.screen.invest.progress

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.invest.model.BuyProgress
import co.electriccoin.zcash.ui.common.invest.model.InvestAssets
import co.electriccoin.zcash.ui.common.invest.repository.InvestRepository
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.invest.common.InvestCurrency
import co.electriccoin.zcash.ui.screen.invest.common.InvestCurrencyProvider
import co.electriccoin.zcash.ui.screen.invest.common.InvestSupport
import co.electriccoin.zcash.ui.screen.invest.common.investCatching
import co.electriccoin.zcash.ui.screen.invest.common.toInvestMessage
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
 * I7. Follows [InvestRepository.observeBuy] until the buy is final. The user may leave at any point: the buy goes
 * on without this screen, and Invest home offers a way back while it is pending.
 */
internal class InvestProgressVM(
    private val args: InvestProgressArgs,
    private val investRepository: InvestRepository,
    currencyProvider: InvestCurrencyProvider,
    private val navigationRouter: NavigationRouter,
) : ViewModel() {
    private val asset = args.assetId?.let(InvestAssets::find)
    private val usdAmount = args.usdAmount?.toBigDecimalOrNull()

    // Money shows in the user's currency, like the PAY balance; USD without an exchange rate.
    private val currency =
        currencyProvider.observe().stateIn(viewModelScope, SharingStarted.Eagerly, InvestCurrency.USD)

    private val progress = MutableStateFlow<BuyProgress?>(null)
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
                    investRepository.observeBuy(args.depositAddress).collect { next ->
                        progress.update { next }
                        if (next is BuyProgress.Held) refreshHoldingsQuietly()
                    }
                }.onFailure { e ->
                    Twig.warn(e) { "InvestProgressVM: observing the buy failed" }
                    checkError.update { e.toInvestMessage() }
                }
            }
    }

    // The new position should be on PAY by the time the user gets back there; a failure only means it shows later.
    private suspend fun refreshHoldingsQuietly() {
        investCatching { investRepository.refreshHoldings() }
            .onFailure { Twig.warn(it) { "InvestProgressVM: holdings refresh after buy failed" } }
    }

    private fun buildState(
        current: BuyProgress?,
        error: StringResource?,
    ): InvestProgressState =
        InvestProgressState(
            title = BuyProgressToSteps.title(current, asset),
            subtitle = BuyProgressToSteps.subtitle(current, asset, usdAmount?.let(currency.value::format)),
            isSuccess = current is BuyProgress.Held,
            steps = BuyProgressToSteps.steps(current ?: BuyProgress.SendingZec(args.depositAddress), asset),
            attention =
                (current as? BuyProgress.NeedsAttention)?.let {
                    stringRes(R.string.invest_progress_attention_body, it.reference)
                },
            checkError = error.takeIf { current?.isFinal != true || current is BuyProgress.NeedsAttention },
            onCheckAgain = ::observe,
            primaryButton = ButtonState(stringRes(R.string.invest_back_to_pay), onClick = ::onBackToPay),
            contactSupportButton =
                (current as? BuyProgress.NeedsAttention)?.let { attention ->
                    ButtonState(stringRes(R.string.invest_contact_support)) {
                        InvestSupport.contact(navigationRouter, InvestSupport.Kind.BUY, attention.reference)
                    }
                },
            removeButton =
                if (current is BuyProgress.NeedsAttention) {
                    ButtonState(stringRes(R.string.invest_progress_remove), onClick = ::onRemove)
                } else {
                    null
                },
            onBack = ::onBack,
        )

    private fun onBackToPay() = navigationRouter.backToRoot()

    // Only the list entry goes: 1Click still settles or refunds the buy, and support has the reference.
    private fun onRemove() {
        viewModelScope.launch {
            investCatching { investRepository.dismissBuy(args.depositAddress) }
                .onSuccess { navigationRouter.back() }
                .onFailure { e ->
                    Twig.warn(e) { "InvestProgressVM: dismissing the buy failed" }
                    checkError.update { stringRes(R.string.invest_error_generic) }
                }
        }
    }

    private fun onBack() = navigationRouter.back()
}
