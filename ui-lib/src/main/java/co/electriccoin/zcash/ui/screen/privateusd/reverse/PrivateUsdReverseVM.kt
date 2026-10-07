// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd.reverse

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapRepository
import co.electriccoin.zcash.ui.common.atomicswap.ReverseSwapRepository
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.privateusd.LocalCurrency
import co.electriccoin.zcash.ui.common.privateusd.ObserveLocalCurrencyUseCase
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdBalanceRepository
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdSpendGuard
import co.electriccoin.zcash.ui.common.repository.RailgunWalletRepository
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
import co.electriccoin.zcash.ui.screen.privateusd.convert.ConvertHoldings
import co.electriccoin.zcash.ui.screen.privateusd.epochSeconds
import co.electriccoin.zcash.ui.screen.privateusd.isLoading
import co.electriccoin.zcash.ui.screen.privateusd.message
import co.electriccoin.zcash.ui.screen.privateusd.progress.PrivateUsdProblemState
import co.electriccoin.zcash.ui.screen.privateusd.progress.PrivateUsdProgressState
import co.electriccoin.zcash.ui.screen.privateusd.quoteFailure
import co.electriccoin.zcash.ui.screen.privateusd.refunds.PrivateUsdRefundsArgs
import co.electriccoin.zcash.ui.screen.privateusd.requireDeployment
import co.electriccoin.zcash.ui.screen.privateusd.runConversionStep
import co.electriccoin.zcash.ui.screen.privateusd.toFailure
import co.electriccoin.zcash.ui.screen.privateusd.zecField
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.justzappit.offramp.atomicswap.ReverseApproval
import xyz.justzappit.offramp.atomicswap.ReversePhase
import xyz.justzappit.offramp.atomicswap.ReverseSwapRecord
import xyz.justzappit.offramp.atomicswap.ReverseSwapResult
import xyz.justzappit.offramp.atomicswap.ReverseSwapStatus
import xyz.justzappit.offramp.p2p.Usdc6
import java.math.BigInteger
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

class PrivateUsdReverseVM(
    private val repository: ReverseSwapRepository,
    private val secretAuthGate: SecretAuthGate,
    private val navigationRouter: NavigationRouter,
    private val navigateBackToPay: NavigateBackToPayUseCase,
    private val atomicSwapRepository: AtomicSwapRepository,
    private val railgunWalletRepository: RailgunWalletRepository,
    private val balanceRepository: PrivateUsdBalanceRepository,
    observeLocalCurrency: ObserveLocalCurrencyUseCase,
    accountDataSource: AccountDataSource,
    private val spendGuard: PrivateUsdSpendGuard,
) : ViewModel() {
    private val terms = PrivateUsdReverseTerms(atomicSwapRepository.requireDeployment())
    private val mapper = PrivateUsdReverseMapper(terms, Actions())
    private val form = MutableStateFlow(ReverseForm())
    private var quoteJob: Job? = null
    private var stepJob: Job? = null
    private val refundRequest =
        PrivateUsdReverseRefundRequest(form, repository, secretAuthGate, viewModelScope) { stepJob }

    private val hasRefunds: Flow<Boolean> =
        repository.history.map { history -> history.any { it.phase == ReversePhase.REFUNDED } }.distinctUntilChanged()

    // Looks each second for a quote that ran out, and replaces it; it emits only when that changes.
    private val quoteExpiry: Flow<Boolean> =
        epochSeconds(1.seconds)
            .onEach(::refreshIfExpired)
            .map { now -> isExpiredQuote(form.value.shown(repository.state.value.record), now) }
            .distinctUntilChanged()

    internal val state: StateFlow<PrivateUsdReverseState> =
        combine(
            combine(repository.state, hasRefunds, quoteExpiry, ::ReverseConversion),
            form,
            combine(
                accountDataSource.zashiAccount.map { it?.spendableShieldedBalance },
                balanceRepository.observe(),
                observeLocalCurrency(),
                spendGuard.state,
                ::ConvertHoldings,
            ),
            secretAuthGate.pinPrompt,
        ) { conversion, form, holdings, pin -> createState(conversion, form, holdings, pin) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
                initialValue =
                    createState(
                        ReverseConversion(repository.state.value, hasRefunds = false, isExpired = false),
                        form.value,
                        ConvertHoldings(
                            null,
                            balanceRepository.state.value,
                            LocalCurrency.DOLLAR,
                            spendGuard.state.value,
                        ),
                        null,
                    ),
            )

    init {
        repository.resume(isForeground = true)
    }

    internal fun resetAmount() {
        if (!form.value.canSwitchDirection(repository.state.value.record)) return
        quoteJob?.cancel()
        form.value =
            ReverseForm(
                hidden =
                    repository.state.value.record
                        ?.takeIf { it.finished }
                        ?.index
            )
    }

    private fun createState(
        conversion: ReverseConversion,
        form: ReverseForm,
        holdings: ConvertHoldings,
        pin: PinVerifyState?,
    ): PrivateUsdReverseState {
        val currency = form.currency ?: holdings.currency
        val record = form.shown(conversion.swap.record)
        val available = terms.spendable(holdings.balance)
        val canPay = form.canPay(record, available, conversion.isExpired) && holdings.spending.canStartConversion
        val primary = mapper.primary(record, form, canPay, conversion.isExpired)
        val review =
            record
                ?.takeIf { it.phase == ReversePhase.QUOTED && form.isReviewing }
                ?.let { mapper.review(it, currency) }
        return PrivateUsdReverseState(
            amount = NumberTextFieldState(form.amount) { onAmountChange(it, currency) },
            isAmountInvalid = form.isInvalid(record, available),
            amountNote = terms.limits(currency),
            currencySymbol = currency.symbol,
            usdAvailable = terms.available(holdings.balance, currency),
            zecAvailable = holdings.spendable?.let { stringRes(it).asPrivacySensitive() },
            receiveEstimate = record?.let { zecField(it.receivedZat()) } ?: NumberTextFieldInnerState(),
            isQuoting = form.step == ReverseStep.Quoting,
            onMax =
                available
                    ?.takeIf { it.signum() > 0 && form.step == ReverseStep.Typing }
                    ?.let { balance -> { onMax(balance, currency) } },
            canSwitchDirection = form.canSwitchDirection(record),
            review = review,
            progress =
                record
                    ?.takeIf { it.phase != ReversePhase.QUOTED }
                    ?.let { mapper.progress(it, conversion, form, currency, primary) },
            refunds = refundsButton(conversion, record, form, review != null),
            error =
                form.message(record, available, conversion.isExpired)
                    ?: holdings.spending.message().takeUnless {
                        holdings.spending.canStartConversion || !form.canAct
                    },
            info = terms.info(currency),
            primary = primary,
            isBackEnabled = form.isBackEnabled,
            pinVerify = pin,
            onBack = ::onBack,
            isZecBalanceLoading = holdings.spendable == null,
            isUsdBalanceLoading = holdings.balance.isLoading,
            usdBalanceError =
                stringRes(R.string.private_usd_load_failed).takeIf {
                    holdings.balance.refreshFailed &&
                        holdings.balance.balances == null
                },
            isUsdBalanceRefreshing = holdings.balance.isRefreshing,
            onRefreshBalance = { balanceRepository.refresh() },
        )
    }

    private fun refundsButton(
        conversion: ReverseConversion,
        record: ReverseSwapRecord?,
        form: ReverseForm,
        isReviewing: Boolean,
    ): ButtonState? {
        if (!conversion.hasRefunds || !form.canAct) return null
        return ButtonState(stringRes(R.string.refunds_view), onClick = Actions()::refunds)
            .takeIf { record?.underWay != true && !isReviewing }
    }

    private fun onAmountChange(
        inner: NumberTextFieldInnerState,
        shown: LocalCurrency,
    ) {
        if (form.value.step !in EDITABLE) return
        quoteJob?.cancel()
        val currency = shown.takeUnless { inner.innerTextFieldState.value.isEmpty() }
        val requested = currency?.let { inner.amount?.let { amount -> terms.amount(amount, it) } }
        form.update {
            it.copy(
                amount = inner,
                currency = currency,
                requested = requested,
                step = if (requested != null) ReverseStep.Quoting else ReverseStep.Typing,
                stale = null,
                error = null,
            )
        }
        if (requested != null) {
            quoteJob =
                viewModelScope.launch {
                    delay(TYPING_DEBOUNCE)
                    requestQuote(requested)
                }
        }
    }

    private fun onMax(
        balance: BigInteger,
        currency: LocalCurrency
    ) = runStep(ReverseStepKind.LOOK_UP, R.string.reverse_error_max) {
        val unshieldFee = railgunWalletRepository.fees().unshieldFee(balance)
        val relayerFee = repository.fundingFee().micros
        form.update { it.copy(step = ReverseStep.Typing) }
        onAmountChange(terms.maximum((balance - unshieldFee - relayerFee).max(BigInteger.ZERO), currency), currency)
    }

    // A quote that ran out while its amount shows is replaced, once: one that doesn't come isn't asked for again.
    private fun refreshIfExpired(now: Long) {
        val current = form.value
        val requested =
            current.requested?.takeIf { current.step == ReverseStep.Typing && it != current.stale } ?: return
        val shown = current.shown(repository.state.value.record)
        if (shown?.quote?.terms?.amount == requested && isExpiredQuote(shown, now)) {
            form.update { it.copy(step = ReverseStep.Quoting) }
            quoteJob = viewModelScope.launch { requestQuote(requested) }
        }
    }

    private suspend fun requestQuote(requested: Usdc6) {
        val quoted = runConversionStep("no reverse quote") { repository.quote(requested) }
        val error =
            quoted.fold(
                onSuccess = { record ->
                    val now = Clock.System.now().epochSeconds
                    stringRes(R.string.convert_quote_clock_ahead).takeIf { isExpiredQuote(record, now) }
                },
                onFailure = { navigationRouter.quoteFailure(it, atomicSwapRepository, repository) },
            )
        form.update {
            it.copy(
                step = ReverseStep.Typing,
                hidden = if (quoted.isSuccess) null else it.hidden,
                stale = requested.takeIf { error != null || quoted.isFailure },
                error = error,
            )
        }
    }

    private fun onReview() {
        if (!spendGuard.state.value.canStartConversion) return
        val current = form.value
        val record = current.shown(repository.state.value.record)
        val available = terms.spendable(balanceRepository.state.value)
        val isExpired = isExpiredQuote(record, Clock.System.now().epochSeconds)
        if (current.step != ReverseStep.Typing || record?.phase != ReversePhase.QUOTED ||
            !current.canPay(record, available, isExpired)
        ) {
            return
        }
        form.update { it.copy(step = ReverseStep.Reviewing, error = null) }
    }

    // Back leaves the review of a preview for its amount, cancels a look-up, waits for an authorized step, else leaves.
    private fun onBack() {
        val current = form.value
        val step = current.step
        val onPreview = current.shown(repository.state.value.record)?.phase == ReversePhase.QUOTED
        when {
            !current.isBackEnabled -> {
                Unit
            }

            step == ReverseStep.Reviewing && onPreview -> {
                form.update { it.copy(step = ReverseStep.Typing, error = null) }
            }

            else -> {
                quoteJob?.cancel()
                stepJob?.takeIf { step is ReverseStep.Acting }?.cancel()
                form.update { if (it.step is ReverseStep.Acting) it.copy(step = ReverseStep.Typing) else it }
                navigateBackToPay()
            }
        }
    }

    private fun runStep(
        kind: ReverseStepKind,
        @StringRes otherwise: Int,
        isFunding: Boolean = false,
        step: suspend () -> Unit,
    ) {
        val from = form.value.step
        if (!form.value.canAct) return
        form.update { it.copy(step = ReverseStep.Acting(from, kind, isFunding = isFunding), error = null) }
        stepJob =
            viewModelScope.launch {
                var error: StringResource? = null
                try {
                    error =
                        runConversionStep("a reverse conversion step didn't go ahead") {
                            if (kind == ReverseStepKind.LOOK_UP || secretAuthGate.authenticateSpend()) {
                                form.update {
                                    val acting = it.step as? ReverseStep.Acting
                                    if (acting == null) it else it.copy(step = acting.copy(isAuthorized = true))
                                }
                                step()
                            }
                        }.exceptionOrNull()?.toFailure()?.message(otherwise)
                } finally {
                    form.update {
                        if (it.step is ReverseStep.Acting) it.copy(step = from, error = it.error ?: error) else it
                    }
                }
            }
    }

    private inner class Actions : PrivateUsdReverseActions {
        override fun review() = onReview()

        // Nothing commits before this: accepting the quote, importing its account and paying for it are one step.
        override fun convert(index: Int) =
            runStep(ReverseStepKind.AUTHORIZED, R.string.convert_start_failed, isFunding = true) {
                repository.fund(index)
            }

        override fun fund(index: Int) =
            runStep(ReverseStepKind.AUTHORIZED, R.string.reverse_error, isFunding = true) { repository.fund(index) }

        override fun ready(index: Int) =
            runStep(ReverseStepKind.AUTHORIZED, R.string.reverse_error) { repository.ready(index) }

        override fun cancel(index: Int) = refundRequest.request(index)

        override fun refunds() = navigationRouter.forward(PrivateUsdRefundsArgs)

        override fun newQuote() {
            form.update { it.copy(step = ReverseStep.Typing, stale = null, error = null) }
            refreshIfExpired(Clock.System.now().epochSeconds)
        }

        override fun newConversion() = resetAmount()

        override fun back() = onBack()
    }

    private companion object {
        val EDITABLE = setOf(ReverseStep.Typing, ReverseStep.Quoting)
    }
}
