// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd.convert

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.exception.SdkException
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapRepository
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.privateusd.DollarRate
import co.electriccoin.zcash.ui.common.privateusd.ObserveDollarRateUseCase
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdTokens
import co.electriccoin.zcash.ui.common.privateusd.local
import co.electriccoin.zcash.ui.common.repository.BiometricRepository
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.privateusd.PrivateUsdInfo
import co.electriccoin.zcash.ui.screen.privateusd.authorizeSpend
import co.electriccoin.zcash.ui.screen.privateusd.progress.PrivateUsdProgressArgs
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.justzappit.atomicswap.AtomicSwapException
import xyz.justzappit.evm.rpc.RpcException
import xyz.justzappit.offramp.atomicswap.AtomicSwapBlock
import xyz.justzappit.offramp.atomicswap.AtomicSwapBlockedException
import xyz.justzappit.offramp.atomicswap.AtomicSwapHttpException
import xyz.justzappit.offramp.atomicswap.AtomicSwapOffer
import xyz.justzappit.offramp.atomicswap.AtomicSwapService
import java.io.IOException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class PrivateUsdConvertVM(
    private val atomicSwapRepository: AtomicSwapRepository,
    private val biometricRepository: BiometricRepository,
    private val navigationRouter: NavigationRouter,
    accountDataSource: AccountDataSource,
    observeDollarRate: ObserveDollarRateUseCase,
) : ViewModel() {
    private val terms =
        checkNotNull(atomicSwapRepository.deployment) { "no conversions in this build" }.let { deployment ->
            PrivateUsdConvertTerms(
                deployment,
                checkNotNull(PrivateUsdTokens.find(deployment.railgunNetwork, deployment.config.token.checksumHex)),
            )
        }
    private val form = MutableStateFlow(ConvertForm())
    private var quoteJob: Job? = null

    // A quote left to run out on the amount step is replaced; on review the user decides.
    private val clock =
        flow {
            while (true) {
                emit(Clock.System.now().epochSeconds)
                delay(1.seconds)
            }
        }.onEach { now ->
            val current = form.value
            val ready = current.quote as? ConvertQuote.Ready
            if (current.phase == PrivateUsdConvertPhase.AMOUNT && ready != null && ready.secondsLeft(now) <= 0) {
                requestQuote(ready.units, Duration.ZERO)
            }
        }

    internal val state: StateFlow<PrivateUsdConvertState> =
        combine(
            form,
            accountDataSource.zashiAccount.map { it?.spendableShieldedBalance },
            observeDollarRate(),
            clock,
            ::createState,
        ).stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue = createState(form.value, null, null, Clock.System.now().epochSeconds),
        )

    private fun createState(
        form: ConvertForm,
        spendable: Zatoshi?,
        rate: DollarRate?,
        now: Long,
    ): PrivateUsdConvertState {
        val ready = form.quote as? ConvertQuote.Ready
        val expired = ready != null && ready.secondsLeft(now) <= 0
        val short = ready != null && terms.isShort(ready, spendable)
        val isAmountInvalid = terms.isInvalid(form.amount)
        return PrivateUsdConvertState(
            phase = form.phase,
            amount = NumberTextFieldState(innerState = form.amount, onValueChange = ::onAmountChange),
            amountNote =
                form.amount.amount
                    ?.takeIf { rate != null && !isAmountInvalid }
                    ?.let { stringRes(R.string.private_usd_worth, rate.local(it)) }
                    ?: terms.limits,
            isAmountInvalid = isAmountInvalid,
            zecAvailable = spendable?.let { stringRes(it) },
            quote = ready?.let { terms.quote(it, now) },
            isQuoting = form.quote is ConvertQuote.Loading,
            message = message(form, spendable?.takeIf { short }, expired),
            info = info(form.phase),
            primaryButton = primaryButton(form, canGoOn = ready != null && !expired && !short, expired = expired),
            onBack = ::onBack,
        )
    }

    private fun message(
        form: ConvertForm,
        shortOf: Zatoshi?,
        expired: Boolean
    ): StringResource? =
        when {
            form.error != null -> form.error
            form.quote is ConvertQuote.Failed -> form.quote.message
            shortOf != null -> stringRes(R.string.convert_insufficient, stringRes(shortOf))
            expired && form.phase == PrivateUsdConvertPhase.REVIEW -> stringRes(R.string.convert_quote_ran_out)
            else -> null
        }

    private fun info(phase: PrivateUsdConvertPhase): PrivateUsdInfo =
        when (phase) {
            PrivateUsdConvertPhase.AMOUNT -> {
                PrivateUsdInfo(
                    title = stringRes(R.string.convert_info_title),
                    steps =
                        listOf(
                            stringRes(R.string.convert_info_step_quote),
                            stringRes(R.string.convert_info_step_deposit),
                            stringRes(R.string.convert_info_step_claim),
                        ),
                    notes =
                        listOf(
                            stringRes(R.string.convert_info_note_either),
                            stringRes(R.string.convert_info_note_time, terms.duration),
                        ),
                )
            }

            PrivateUsdConvertPhase.REVIEW -> {
                PrivateUsdInfo(
                    title = stringRes(R.string.convert_review_info_title),
                    notes =
                        listOf(
                            stringRes(R.string.convert_review_info_pay),
                            stringRes(R.string.convert_review_info_receive),
                            stringRes(R.string.convert_review_info_quote),
                            stringRes(R.string.convert_info_note_either),
                            stringRes(R.string.convert_info_note_time, terms.duration),
                        ),
                )
            }
        }

    private fun primaryButton(
        form: ConvertForm,
        canGoOn: Boolean,
        expired: Boolean,
    ): ButtonState =
        when {
            form.phase == PrivateUsdConvertPhase.AMOUNT -> {
                ButtonState(stringRes(R.string.convert_review), isEnabled = canGoOn) {
                    this.form.update { it.copy(phase = PrivateUsdConvertPhase.REVIEW, error = null) }
                }
            }

            expired -> {
                ButtonState(stringRes(R.string.convert_new_quote)) {
                    val units = (this.form.value.quote as? ConvertQuote.Ready)?.units
                    this.form.update { it.copy(phase = PrivateUsdConvertPhase.AMOUNT, error = null) }
                    units?.let { requestQuote(it, Duration.ZERO) }
                }
            }

            else -> {
                ButtonState(
                    text = stringRes(R.string.convert_confirm),
                    isEnabled = canGoOn && !form.isConfirming,
                    isLoading = form.isConfirming,
                    onClick = ::onConfirm,
                )
            }
        }

    private fun onAmountChange(inner: NumberTextFieldInnerState) {
        form.update { it.copy(amount = inner, error = null) }
        val units = terms.units(inner)
        if (units != null) {
            requestQuote(units, TYPING_DEBOUNCE)
        } else {
            quoteJob?.cancel()
            form.update { it.copy(quote = ConvertQuote.None) }
        }
    }

    private fun onBack() {
        val current = form.value
        when {
            current.isConfirming -> {
                Unit
            }

            current.phase == PrivateUsdConvertPhase.REVIEW -> {
                form.update { it.copy(phase = PrivateUsdConvertPhase.AMOUNT) }
            }

            else -> {
                navigationRouter.back()
            }
        }
    }

    private fun requestQuote(
        units: Int,
        debounce: Duration
    ) {
        quoteJob?.cancel()
        form.update { it.copy(quote = ConvertQuote.Loading(units)) }
        quoteJob =
            viewModelScope.launch {
                delay(debounce)
                val quote =
                    try {
                        ConvertQuote.Ready(units, atomicSwapRepository.quote(units))
                    } catch (e: AtomicSwapBlockedException) {
                        if (e.reason == AtomicSwapBlock.SWAP_UNDER_WAY) navigationRouter.replace(PrivateUsdProgressArgs)
                        noQuote(e, R.string.convert_error_generic)
                    } catch (e: AtomicSwapHttpException) {
                        noQuote(
                            e,
                            if (e.service == AtomicSwapService.RELAYER) {
                                R.string.convert_error_relayer
                            } else {
                                R.string.convert_error_maker
                            }
                        )
                    } catch (e: RpcException) {
                        noQuote(e, R.string.convert_error_generic)
                    } catch (e: SdkException) {
                        noQuote(e, R.string.convert_error_generic)
                    } catch (e: IOException) {
                        noQuote(e, R.string.convert_error_generic)
                    } catch (e: AtomicSwapException) {
                        noQuote(e, R.string.convert_error_generic)
                    }
                form.update { it.copy(quote = quote) }
            }
    }

    private fun onConfirm() {
        val offer = (form.value.quote as? ConvertQuote.Ready)?.quote?.offer ?: return
        if (form.value.isConfirming) return
        viewModelScope.launch {
            if (!biometricRepository.authorizeSpend()) return@launch
            form.update { it.copy(isConfirming = true, error = null) }
            val error = start(offer)
            form.update { it.copy(isConfirming = false, error = error) }
        }
    }

    /** Accepts [offer] and moves on to its progress; the error to show when it didn't start. */
    private suspend fun start(offer: AtomicSwapOffer): StringResource? {
        val failure =
            try {
                atomicSwapRepository.accept(offer)
                null
            } catch (e: AtomicSwapBlockedException) {
                e
            } catch (e: AtomicSwapHttpException) {
                e
            } catch (e: RpcException) {
                e
            } catch (e: SdkException) {
                e
            } catch (e: IOException) {
                e
            } catch (e: AtomicSwapException) {
                e
            }
        failure?.let { Twig.warn(it) { "Private USD: the conversion didn't start" } }
        val expired = (failure as? AtomicSwapBlockedException)?.reason == AtomicSwapBlock.QUOTE_EXPIRED
        // An accept that failed after the swap was recorded is under way all the same.
        return when {
            failure == null || atomicSwapRepository.isUnderWay() -> {
                navigationRouter.replace(PrivateUsdProgressArgs)
                null
            }

            expired -> {
                stringRes(R.string.convert_quote_ran_out)
            }

            else -> {
                stringRes(R.string.convert_start_failed)
            }
        }
    }

    private companion object {
        val TYPING_DEBOUNCE = 700.milliseconds

        fun noQuote(
            e: Exception,
            message: Int
        ): ConvertQuote {
            Twig.warn(e) { "Private USD: no quote" }
            return ConvertQuote.Failed(stringRes(message))
        }
    }
}
