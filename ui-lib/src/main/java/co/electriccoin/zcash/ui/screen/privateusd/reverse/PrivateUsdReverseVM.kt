// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd.reverse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.backToPay
import co.electriccoin.zcash.ui.common.atomicswap.ReverseSwapRepository
import co.electriccoin.zcash.ui.common.atomicswap.label
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.privateusd.ObserveConversionCurrencyUseCase
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdBalanceRepository
import co.electriccoin.zcash.ui.common.privateusd.format
import co.electriccoin.zcash.ui.common.repository.BiometricRepository
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldState
import co.electriccoin.zcash.ui.design.util.asPrivacySensitive
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.privateusd.authorizeSpend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.justzappit.offramp.atomicswap.ReversePhase
import java.math.BigDecimal
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class PrivateUsdReverseVM(
    private val repository: ReverseSwapRepository,
    private val biometric: BiometricRepository,
    private val navigation: NavigationRouter,
    balanceRepository: PrivateUsdBalanceRepository,
    observeConversionCurrency: ObserveConversionCurrencyUseCase,
    accountDataSource: AccountDataSource,
) : ViewModel() {
    private val form = MutableStateFlow(Form())
    private var quoteJob: Job? = null
    private val currencyState = observeConversionCurrency().stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val recoverableIndex =
        flow {
            emit(null)
            while (true) {
                val record = repository.state.value.record
                emit(record?.takeIf { it.phase == ReversePhase.REFUNDED && repository.canRescue(it.index) }?.index)
                delay(15.seconds)
            }
        }
    internal val state =
        combine(
            combine(repository.state, recoverableIndex) { progress, index -> progress to index },
            form,
            balanceRepository.observe(),
            currencyState,
            accountDataSource.zashiAccount.map { it?.spendableShieldedBalance },
        ) { (progress, recoverableIndex), form, balance, currency, zecBalance ->
            val saved = progress.record.takeUnless { form.newQuote }
            val input = form.amount
            val record =
                saved?.takeUnless {
                    it.phase == ReversePhase.QUOTED && units(input)?.toString() != it.quote.terms.amount
                }
            val phase = record?.phase
            val available = balance.reverseAvailable()
            val enteredUnits = units(input)
            val debit = record?.cost?.debit?.toBigInteger() ?: enteredUnits?.toBigInteger()
            val insufficient = record?.funding == null && available != null && debit != null && debit > available
            val canPay = available != null && debit != null && !insufficient && currency?.perDollar != null
            val primary =
                when {
                    form.quoting -> {
                        ButtonState(stringRes(R.string.convert_quote_loading), isEnabled = false, isLoading = true)
                    }

                    record == null || phase == ReversePhase.QUOTED -> {
                        ButtonState(
                            stringRes(R.string.convert_review),
                            isEnabled =
                                record != null && canPay && !form.quoting
                        ) {
                            runAction(false) { repository.review(checkNotNull(record).index) }
                        }
                    }

                    phase == ReversePhase.AWAITING_FUNDING -> {
                        ButtonState(stringRes(R.string.convert_confirm), isEnabled = canPay) {
                            runAction(true) {
                                this.form.update { it.copy(funding = true) }
                                try {
                                    repository.fund(record.index)
                                } finally {
                                    this.form.update { it.copy(funding = false) }
                                }
                            }
                        }
                    }

                    phase == ReversePhase.AWAITING_READY -> {
                        ButtonState(stringRes(R.string.reverse_ready)) {
                            runAction(true) { repository.ready(record.index) }
                        }
                    }

                    record.finished -> {
                        ButtonState(stringRes(R.string.reverse_new), onClick = ::resetAmount)
                    }

                    else -> {
                        ButtonState(stringRes(R.string.reverse_refresh)) { runAction(false) { repository.refresh() } }
                    }
                }
            val state =
                PrivateUsdReverseState(
                    amount = NumberTextFieldState(input, onValueChange = ::onAmountChange),
                    currencySymbol = currency?.symbol.orEmpty(),
                    showAmount = record == null || phase == ReversePhase.QUOTED,
                    zecAvailable = zecBalance?.let { stringRes(it).asPrivacySensitive() },
                    zecEstimate =
                        record?.let {
                            BigDecimal.valueOf(
                                it.quote.terms.depositZat - MIN_SWEEP_FEE,
                                ZEC_DECIMALS
                            )
                        },
                    isQuoting = form.quoting,
                    onMax =
                        available
                            ?.takeIf {
                                it.signum() > 0 && !form.busy && currency?.perDollar != null
                            }?.let { maximum ->
                                {
                                    runAction(false) {
                                        val units = repository.maximum(maximum).min(MAX_UNITS.toBigInteger())
                                        onAmountChange(
                                            NumberTextFieldInnerState.fromAmount(
                                                checkNotNull(currency?.maximum(BigDecimal(units, USDC_DECIMALS)))
                                            )
                                        )
                                    }
                                }
                            },
                    showReview = phase == ReversePhase.AWAITING_FUNDING && !form.funding,
                    available =
                        available?.let {
                            currency.format(BigDecimal(it, USDC_DECIMALS)).asPrivacySensitive()
                        }
                            ?: stringRes(
                                if (balance.refreshFailed) {
                                    R.string.reverse_balance_unavailable
                                } else {
                                    R.string.reverse_balance_loading
                                }
                            ),
                    amountNote =
                        stringRes(
                            R.string.convert_local_limits,
                            currency.format(BigDecimal.valueOf(MIN_UNITS.toLong(), USDC_DECIMALS)),
                            currency.format(BigDecimal.valueOf(MAX_UNITS.toLong(), USDC_DECIMALS)),
                        ),
                    isAmountInvalid = insufficient || (input.amount != null && enteredUnits == null),
                    status =
                        if (phase == ReversePhase.RECEIVING && record.receiveConfirmations > 0) {
                            stringRes(R.string.reverse_sweep_confirmations, record.receiveConfirmations)
                        } else {
                            stringRes(phase.label())
                        },
                    escrow =
                        record
                            ?.quote
                            ?.terms
                            ?.amount
                            ?.let { currency.format(BigDecimal(it).movePointLeft(USDC_DECIMALS)) },
                    debit = record?.cost?.debit?.let { currency.format(BigDecimal(it).movePointLeft(USDC_DECIMALS)) },
                    railgunFee =
                        record?.cost?.railgunFee?.let {
                            currency.format(
                                BigDecimal(it).movePointLeft(USDC_DECIMALS)
                            )
                        },
                    broadcasterFee =
                        record?.cost?.broadcasterFee?.let {
                            currency.format(
                                BigDecimal(it).movePointLeft(USDC_DECIMALS)
                            )
                        },
                    receive =
                        record?.let {
                            zec(
                                it.receive?.receivedZat ?: it.receiveEstimate?.receivedZat
                                    ?: (it.quote.terms.depositZat - MIN_SWEEP_FEE).coerceAtLeast(0)
                            )
                        },
                    receiveIsEstimate = record?.receive == null,
                    error =
                        when {
                            form.failed || progress.failed -> stringRes(R.string.reverse_error)
                            insufficient -> stringRes(R.string.reverse_insufficient)
                            else -> null
                        },
                    primary =
                        primary.copy(
                            isEnabled = primary.isEnabled && !form.busy,
                            isLoading =
                                form.busy || form.quoting
                        ),
                    cancel =
                        record?.takeUnless { !it.underWay || it.cancelRequested || it.receive != null }?.let {
                            ButtonState(
                                stringRes(R.string.reverse_cancel),
                                isEnabled = !form.busy
                            ) { runAction(true) { repository.cancel(it.index) } }
                        },
                    rescue =
                        record?.takeIf { it.phase == ReversePhase.REFUNDED && it.index == recoverableIndex }?.let {
                            ButtonState(
                                stringRes(R.string.reverse_rescue),
                                isEnabled = !form.busy
                            ) { runAction(true) { repository.rescue(it.index) } }
                        },
                    onBack = navigation::backToPay,
                )
            state.copy(progress = record?.let { reverseProgress(it, state) })
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            PrivateUsdReverseState(
                NumberTextFieldState(NumberTextFieldInnerState()) {},
                true,
                stringRes(R.string.reverse_intro),
                primary = ButtonState(stringRes(R.string.convert_review), isEnabled = false),
                onBack = navigation::backToPay,
            )
        )

    init {
        repository.resume(true)
        viewModelScope.launch {
            while (true) {
                delay(1.seconds)
                refreshQuoteIfExpired()
            }
        }
    }

    private fun refreshQuoteIfExpired() {
        val saved = repository.state.value.record
        val current = form.value
        val canRefresh =
            form.subscriptionCount.value > 0 && !current.quoting && !current.busy && saved?.underWay != true
        val inputUnits = units(current.amount)
        val expired =
            saved?.phase == ReversePhase.QUOTED &&
                saved.quote.terms.expiresAt <= Clock.System.now().epochSeconds + QUOTE_MARGIN
        val needsQuote = inputUnits != current.requestedUnits || expired
        if (canRefresh && inputUnits != null && needsQuote) {
            onAmountChange(current.amount)
        }
    }

    internal fun resetAmount() {
        if (form.value.busy || repository.state.value.record
                ?.underWay == true
        ) {
            return
        }
        quoteJob?.cancel()
        form.value = Form(newQuote = true)
    }

    private fun units(amount: NumberTextFieldInnerState): Int? =
        amount.amount?.let { currencyState.value?.units(it) }?.takeIf { it in MIN_UNITS..MAX_UNITS }

    private fun onAmountChange(inner: NumberTextFieldInnerState) {
        quoteJob?.cancel()
        val units = units(inner)
        form.update { it.copy(amount = inner, requestedUnits = units, quoting = units != null, failed = false) }
        if (units == null) return
        quoteJob =
            viewModelScope.launch {
                delay(700.milliseconds)
                requestQuote(units)
            }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun requestQuote(units: Int) {
        try {
            repository.quote(units)
            form.update { it.copy(quoting = false, newQuote = false) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Twig.warn(e) { "Reverse conversion quote unavailable" }
            form.update { it.copy(quoting = false, failed = true) }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun runAction(authorize: Boolean, action: suspend () -> Unit) {
        if (form.value.busy) return
        form.update { it.copy(busy = true, failed = false) }
        viewModelScope.launch {
            try {
                if (!authorize || biometric.authorizeSpend()) action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Twig.warn(e) { "Reverse conversion action interrupted" }
                form.update { it.copy(failed = true) }
            } finally {
                form.update { it.copy(busy = false) }
            }
        }
    }

    private data class Form(
        val amount: NumberTextFieldInnerState = NumberTextFieldInnerState(),
        val busy: Boolean = false,
        val funding: Boolean = false,
        val failed: Boolean = false,
        val newQuote: Boolean = false,
        val requestedUnits: Int? = null,
        val quoting: Boolean = false,
    )

    private companion object {
        const val USDC_DECIMALS = 6
        const val ZEC_DECIMALS = 8
        const val MIN_UNITS = 110_000
        const val MAX_UNITS = 20_000_000
        const val MIN_SWEEP_FEE = 10_000L
        const val QUOTE_MARGIN = 20L

        fun zec(zat: Long): String = BigDecimal.valueOf(zat, ZEC_DECIMALS).stripTrailingZeros().toPlainString()
    }
}
