// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd.progress

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapRepository
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapState
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdBalanceRepository
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdBalanceState
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdTokens
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.privateusd.PrivateUsdArgs
import co.electriccoin.zcash.ui.screen.privateusd.convert.PrivateUsdConvertArgs
import co.electriccoin.zcash.ui.screen.privateusd.message
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import xyz.justzappit.offramp.atomicswap.AtomicSwapBlockedException
import xyz.justzappit.offramp.atomicswap.AtomicSwapOutcome
import xyz.justzappit.offramp.atomicswap.AtomicSwapWait
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

class PrivateUsdProgressVM(
    private val atomicSwapRepository: AtomicSwapRepository,
    balanceRepository: PrivateUsdBalanceRepository,
    private val navigationRouter: NavigationRouter,
) : ViewModel() {
    private val steps =
        checkNotNull(atomicSwapRepository.deployment) { "no conversions in this build" }.let { deployment ->
            PrivateUsdProgressSteps(
                deployment,
                checkNotNull(PrivateUsdTokens.find(deployment.railgunNetwork, deployment.config.token.checksumHex)),
            )
        }

    private val clock =
        flow {
            while (true) {
                emit(Clock.System.now().epochSeconds)
                delay(TICK)
            }
        }

    internal val state: StateFlow<PrivateUsdProgressState> =
        combine(atomicSwapRepository.state, balanceRepository.observe(), clock, ::createState)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
                initialValue =
                    createState(
                        atomicSwapRepository.state.value,
                        balanceRepository.state.value,
                        Clock.System.now().epochSeconds,
                    ),
            )

    init {
        atomicSwapRepository.resume(isForeground = true)
    }

    private fun createState(
        swap: AtomicSwapState,
        balance: PrivateUsdBalanceState,
        now: Long,
    ): PrivateUsdProgressState {
        val record = swap.record
        val isSlowToOpen =
            swap.wait?.reason == AtomicSwapWait.OPENING && record != null && now - record.acceptedAt > SLOW_OPEN_SECONDS
        return PrivateUsdProgressState(
            amounts = record?.let(steps::amounts),
            result = record?.let { steps.result(it, balance) },
            steps = steps.of(swap, balance),
            note = steps.note(swap, isSlowToOpen).takeIf { swap.isUnderWay },
            problem = swap.problem?.takeIf { swap.isUnderWay }?.message(),
            onRetry = atomicSwapRepository::retryNow,
            callOff =
                ButtonState(stringRes(R.string.convert_call_off), onClick = ::onCallOff)
                    .takeIf { isSlowToOpen && swap.problem == null },
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

                    else -> {
                        ButtonState(stringRes(R.string.convert_result_try_again)) {
                            navigationRouter.replace(PrivateUsdConvertArgs)
                        }
                    }
                },
            info = steps.info,
            onBack = navigationRouter::back,
        )
    }

    private fun onCallOff() {
        viewModelScope.launch {
            try {
                atomicSwapRepository.abandon()
            } catch (e: AtomicSwapBlockedException) {
                // It reached the chain meanwhile, so it carries on.
                Twig.info { "Private USD: not called off, ${e.message}" }
            }
        }
    }

    private companion object {
        val TICK = 15.seconds
        const val SLOW_OPEN_SECONDS = 90L
    }
}
