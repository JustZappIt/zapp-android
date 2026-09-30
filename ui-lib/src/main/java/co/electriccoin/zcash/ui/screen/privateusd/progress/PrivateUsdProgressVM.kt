// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd.progress

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapRepository
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapState
import co.electriccoin.zcash.ui.common.atomicswap.ReverseSwapRepository
import co.electriccoin.zcash.ui.common.privateusd.LocalCurrency
import co.electriccoin.zcash.ui.common.privateusd.ObserveLocalCurrencyUseCase
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdBalanceRepository
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdBalanceState
import co.electriccoin.zcash.ui.common.usecase.NavigateBackToPayUseCase
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.privateusd.PrivateUsdArgs
import co.electriccoin.zcash.ui.screen.privateusd.convert.PrivateUsdConvertArgs
import co.electriccoin.zcash.ui.screen.privateusd.epochSeconds
import co.electriccoin.zcash.ui.screen.privateusd.message
import co.electriccoin.zcash.ui.screen.privateusd.requireDeployment
import co.electriccoin.zcash.ui.screen.privateusd.runConversionStep
import co.electriccoin.zcash.ui.screen.privateusd.showConversionUnderWay
import co.electriccoin.zcash.ui.screen.privateusd.toFailure
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import xyz.justzappit.offramp.atomicswap.AtomicSwapOutcome
import xyz.justzappit.offramp.atomicswap.AtomicSwapWait
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

class PrivateUsdProgressVM(
    private val atomicSwapRepository: AtomicSwapRepository,
    private val reverseSwapRepository: ReverseSwapRepository,
    balanceRepository: PrivateUsdBalanceRepository,
    observeLocalCurrency: ObserveLocalCurrencyUseCase,
    private val navigationRouter: NavigationRouter,
    private val navigateBackToPay: NavigateBackToPayUseCase,
) : ViewModel() {
    private val steps = PrivateUsdProgressSteps(atomicSwapRepository.requireDeployment())
    private val callOff = MutableStateFlow(CallOff())

    internal val state: StateFlow<PrivateUsdProgressState> =
        combine(
            atomicSwapRepository.state,
            balanceRepository.observe(),
            epochSeconds(TICK),
            observeLocalCurrency(),
            callOff,
            ::createState
        ).stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue =
                createState(
                    atomicSwapRepository.state.value,
                    balanceRepository.state.value,
                    Clock.System.now().epochSeconds,
                    LocalCurrency.DOLLAR,
                    callOff.value,
                ),
        )

    init {
        atomicSwapRepository.resume(isForeground = true)
        viewModelScope.launch { leaveIfNothingToShow() }
    }

    private fun createState(
        swap: AtomicSwapState,
        balance: PrivateUsdBalanceState,
        now: Long,
        currency: LocalCurrency,
        callOff: CallOff,
    ): PrivateUsdProgressState {
        val record = swap.record
        val isSlowToOpen =
            swap.wait?.reason == AtomicSwapWait.OPENING && record != null && now - record.acceptedAt > SLOW_OPEN_SECONDS
        return PrivateUsdProgressState(
            amounts = record?.let { steps.amounts(it, currency) },
            result = record?.let { steps.result(it, balance, currency) },
            steps = steps.of(swap, balance),
            note = steps.note(swap, now, isSlowToOpen).takeIf { swap.isUnderWay },
            problem =
                swap.problem
                    ?.takeIf { swap.isUnderWay }
                    ?.let { PrivateUsdProblemState(it.message(), onRetry = atomicSwapRepository::retryNow) },
            error = callOff.error,
            callOff =
                ButtonState(
                    text = stringRes(R.string.convert_call_off),
                    isEnabled = !callOff.isBusy,
                    isLoading = callOff.isBusy,
                    onClick = ::onCallOff,
                ).takeIf { isSlowToOpen && swap.problem == null },
            showsBackgroundNote = swap.isUnderWay,
            primaryButton =
                when (record?.outcome) {
                    null -> {
                        null
                    }

                    AtomicSwapOutcome.Paid -> {
                        ButtonState(stringRes(R.string.convert_result_view_balance)) {
                            navigationRouter.replaceAll(PrivateUsdArgs)
                        }
                    }

                    is AtomicSwapOutcome.Refunded, is AtomicSwapOutcome.NothingSent -> {
                        ButtonState(stringRes(R.string.convert_result_try_again)) {
                            navigationRouter.replace(PrivateUsdConvertArgs)
                        }
                    }
                },
            info = steps.info,
            onBack = navigateBackToPay::invoke,
        )
    }

    // A forward conversion is what this screen shows: without one, the reverse one under way or a new one.
    private suspend fun leaveIfNothingToShow() {
        val kept = runConversionStep("no conversion history") { atomicSwapRepository.history.first() }.getOrNull()
        if (kept.isNullOrEmpty() &&
            !navigationRouter.showConversionUnderWay(atomicSwapRepository, reverseSwapRepository)
        ) {
            navigationRouter.replace(PrivateUsdConvertArgs)
        }
    }

    private fun onCallOff() {
        if (callOff.value.isBusy) return
        callOff.value = CallOff(isBusy = true)
        viewModelScope.launch {
            val error =
                runConversionStep("not called off") { atomicSwapRepository.abandon() }
                    .exceptionOrNull()
                    ?.toFailure()
                    ?.message(R.string.convert_call_off_failed)
            callOff.value = CallOff(error = error)
        }
    }

    private data class CallOff(
        val isBusy: Boolean = false,
        val error: StringResource? = null,
    )

    private companion object {
        val TICK = 15.seconds
        const val SLOW_OPEN_SECONDS = 90L
    }
}
