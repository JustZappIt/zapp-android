// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd.convert

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapRepository
import co.electriccoin.zcash.ui.common.atomicswap.ReverseSwapRepository
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.privateusd.LocalCurrency
import co.electriccoin.zcash.ui.common.privateusd.ObserveLocalCurrencyUseCase
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdBalanceRepository
import co.electriccoin.zcash.ui.common.security.PinVerifyState
import co.electriccoin.zcash.ui.common.security.SecretAuthGate
import co.electriccoin.zcash.ui.common.usecase.NavigateBackToPayUseCase
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.asPrivacySensitive
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.privateusd.TYPING_DEBOUNCE
import co.electriccoin.zcash.ui.screen.privateusd.authenticateSpend
import co.electriccoin.zcash.ui.screen.privateusd.epochSeconds
import co.electriccoin.zcash.ui.screen.privateusd.isPositive
import co.electriccoin.zcash.ui.screen.privateusd.progress.PrivateUsdProgressArgs
import co.electriccoin.zcash.ui.screen.privateusd.quoteFailure
import co.electriccoin.zcash.ui.screen.privateusd.requireDeployment
import co.electriccoin.zcash.ui.screen.privateusd.runConversionStep
import co.electriccoin.zcash.ui.screen.privateusd.showConversionUnderWay
import co.electriccoin.zcash.ui.screen.privateusd.toFailure
import co.electriccoin.zcash.ui.screen.privateusd.zatoshi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.justzappit.offramp.atomicswap.AtomicSwapOffer
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class PrivateUsdConvertVM(
    private val atomicSwapRepository: AtomicSwapRepository,
    private val reverseSwapRepository: ReverseSwapRepository,
    private val secretAuthGate: SecretAuthGate,
    private val navigationRouter: NavigationRouter,
    private val navigateBackToPay: NavigateBackToPayUseCase,
    accountDataSource: AccountDataSource,
    balanceRepository: PrivateUsdBalanceRepository,
    observeLocalCurrency: ObserveLocalCurrencyUseCase,
) : ViewModel() {
    private val deployment = atomicSwapRepository.requireDeployment()
    private val terms = PrivateUsdConvertTerms(deployment)
    private val zecQuotes = PrivateUsdZecQuotes(atomicSwapRepository, deployment)
    private val form = MutableStateFlow(ConvertForm())
    private var quoteJob: Job? = null

    private val holdings: StateFlow<ConvertHoldings> =
        combine(
            accountDataSource.zashiAccount.map { it?.spendableShieldedBalance },
            balanceRepository.observe(),
            observeLocalCurrency(),
            ::ConvertHoldings,
        ).stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue = ConvertHoldings(null, balanceRepository.state.value, LocalCurrency.DOLLAR),
        )

    internal val state: StateFlow<PrivateUsdConvertState> =
        combine(
            form,
            holdings,
            epochSeconds(1.seconds).onEach(::refreshIfExpired),
            secretAuthGate.pinPrompt,
            ::createState,
        ).stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue = createState(form.value, holdings.value, now(), null),
        )

    init {
        // Back after the process died mid-accept, or opened while a conversion is under way.
        viewModelScope.launch { navigationRouter.showConversionUnderWay(atomicSwapRepository, reverseSwapRepository) }
    }

    internal fun resetAmount() {
        if (form.value.isConfirming) return
        quoteJob?.cancel()
        form.value = ConvertForm()
    }

    private fun createState(
        form: ConvertForm,
        holdings: ConvertHoldings,
        now: Long,
        pin: PinVerifyState?,
    ): PrivateUsdConvertState {
        val ready = form.ready
        val spendable = holdings.spendable
        return PrivateUsdConvertState(
            phase = form.phase,
            amount = NumberTextFieldState(innerState = form.amount, onValueChange = ::onAmountChange),
            isAmountInvalid = form.isShort(spendable) || (form.amount.isPositive && form.amount.zatoshi() == null),
            zecAvailable = spendable?.let { stringRes(it).asPrivacySensitive() },
            usdAvailable = terms.available(holdings),
            currencySymbol = holdings.currency.symbol,
            receiveEstimate = ready?.let { terms.estimate(it.quote, holdings.currency) } ?: NumberTextFieldInnerState(),
            onMax =
                spendable
                    ?.takeIf { form.phase == PrivateUsdConvertPhase.AMOUNT && it.value > 0 }
                    ?.let { available -> { requestQuote(fillsAmount = true) { zecQuotes.maximum(available.value) } } },
            quote = ready?.let { terms.quote(it, form.phase, now, holdings.currency) },
            isQuoting = form.quote == ConvertQuote.Loading,
            message = form.message(spendable, now),
            canSwitchDirection = !form.isConfirming,
            info = terms.info(form.phase),
            primaryButton = primaryButton(form, form.canGoOn(spendable, now), form.isExpired(now)),
            isBackEnabled = !form.isConfirming,
            pinVerify = pin,
            onBack = ::onBack,
        )
    }

    private fun primaryButton(
        form: ConvertForm,
        canGoOn: Boolean,
        expired: Boolean,
    ): ButtonState =
        when {
            form.quote == ConvertQuote.Loading -> {
                ButtonState(stringRes(R.string.convert_quote_loading), isEnabled = false, isLoading = true)
            }

            form.phase == PrivateUsdConvertPhase.AMOUNT -> {
                ButtonState(stringRes(R.string.convert_review), isEnabled = canGoOn, onClick = ::onReview)
            }

            expired -> {
                ButtonState(stringRes(R.string.convert_new_quote)) {
                    this.form.update { it.copy(phase = PrivateUsdConvertPhase.AMOUNT, error = null) }
                    refreshIfExpired(now())
                }
            }

            else -> {
                ButtonState(
                    text = stringRes(R.string.private_usd_action_convert),
                    isEnabled = canGoOn && !form.isConfirming,
                    isLoading = form.isConfirming,
                    onClick = ::onConfirm,
                )
            }
        }

    private fun onAmountChange(inner: NumberTextFieldInnerState) {
        quoteJob?.cancel()
        form.update { it.copy(amount = inner, quote = ConvertQuote.None, error = null) }
        val totalZat = inner.zatoshi()
        if (totalZat != null && !form.value.isShort(holdings.value.spendable)) {
            requestQuote(TYPING_DEBOUNCE) { zecQuotes.quote(totalZat) }
        }
    }

    // A quote left to run out on the amount step is replaced; on review the user decides.
    private fun refreshIfExpired(now: Long) {
        val current = form.value
        val onAmount = current.phase == PrivateUsdConvertPhase.AMOUNT
        val expired = current.ready?.quote?.takeIf { onAmount && current.isExpired(now) }
        val totalZat = current.amount.zatoshi()?.takeUnless { current.isShort(holdings.value.spendable) }
        if (expired != null && totalZat != null) requestQuote { zecQuotes.quote(totalZat, expired.offer.requested) }
    }

    private fun requestQuote(
        debounce: Duration = Duration.ZERO,
        fillsAmount: Boolean = false,
        quote: suspend () -> ZecQuote,
    ) {
        quoteJob?.cancel()
        form.update { it.copy(quote = ConvertQuote.Loading, error = null) }
        quoteJob =
            viewModelScope.launch {
                delay(debounce)
                val next: ConvertQuote =
                    runConversionStep("no quote") { quote() }.fold(
                        onSuccess = { ConvertQuote.Ready(it).arrived(now()) },
                        onFailure = { e ->
                            navigationRouter
                                .quoteFailure(e, atomicSwapRepository, reverseSwapRepository)
                                ?.let(ConvertQuote::Failed) ?: ConvertQuote.None
                        },
                    )
                form.update { it.withQuote(next, fillsAmount) }
            }
    }

    private fun onReview() {
        if (form.value.phase == PrivateUsdConvertPhase.AMOUNT && form.value.canGoOn(holdings.value.spendable, now())) {
            form.update { it.copy(phase = PrivateUsdConvertPhase.REVIEW, error = null) }
        }
    }

    private fun onConfirm() {
        val current = form.value
        val ready = current.ready ?: return
        val onReview = current.phase == PrivateUsdConvertPhase.REVIEW
        if (!onReview || current.isConfirming || !current.canGoOn(holdings.value.spendable, now())) return
        form.update { it.copy(isConfirming = true, error = null) }
        viewModelScope.launch {
            try {
                val error = start(ready.quote.offer)
                form.update { it.copy(error = error) }
            } finally {
                form.update { it.copy(isConfirming = false) }
            }
        }
    }

    /** Accepts [offer] once the user unlocks the spend, then shows its progress; what to say when it didn't start. */
    private suspend fun start(offer: AtomicSwapOffer): StringResource? {
        val step =
            runConversionStep("the conversion didn't start") {
                secretAuthGate.authenticateSpend().also { if (it) atomicSwapRepository.accept(offer) }
            }
        if (step.getOrNull() == true) navigationRouter.replace(PrivateUsdProgressArgs)
        val failure = step.exceptionOrNull()?.toFailure() ?: return null
        // An accept that failed after the swap was recorded is under way all the same.
        val underWay = navigationRouter.showConversionUnderWay(atomicSwapRepository, reverseSwapRepository)
        return failure.takeUnless { underWay }?.message(R.string.convert_start_failed)
    }

    private fun onBack() {
        val current = form.value
        when {
            current.isConfirming -> {
                Unit
            }

            current.phase == PrivateUsdConvertPhase.REVIEW -> {
                form.update { it.copy(phase = PrivateUsdConvertPhase.AMOUNT, error = null) }
            }

            else -> {
                navigateBackToPay()
            }
        }
    }

    private companion object {
        fun now() = Clock.System.now().epochSeconds
    }
}
