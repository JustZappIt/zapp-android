package co.electriccoin.zcash.ui.screen.invest.demo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.invest.demo.DemoInvestEngine
import co.electriccoin.zcash.ui.common.invest.demo.InvestDemoControls
import co.electriccoin.zcash.ui.common.invest.demo.InvestDemoOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

internal data class InvestDemoControlsState(
    val outcome: InvestDemoOutcome,
    val hasLiquidity: Boolean,
    /** Set right after a reset, until something else changes. */
    val isReset: Boolean,
    val onOutcome: (InvestDemoOutcome) -> Unit,
    val onLiquidity: (Boolean) -> Unit,
    val onReset: () -> Unit,
    val onBack: () -> Unit,
)

/** Steers the demo engine: how the next trade ends, whether stocks quote, and a clean slate. Demo builds only. */
internal class InvestDemoControlsVM(
    private val controls: InvestDemoControls,
    private val engine: DemoInvestEngine,
    private val navigationRouter: NavigationRouter,
) : ViewModel() {
    private val isReset = MutableStateFlow(false)

    val state: StateFlow<InvestDemoControlsState> =
        combine(controls.outcome, controls.hasLiquidity, isReset, ::buildState)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
                initialValue = buildState(controls.outcome.value, controls.hasLiquidity.value, isReset = false),
            )

    private fun buildState(
        outcome: InvestDemoOutcome,
        hasLiquidity: Boolean,
        isReset: Boolean,
    ) = InvestDemoControlsState(
        outcome = outcome,
        hasLiquidity = hasLiquidity,
        isReset = isReset,
        onOutcome = ::onOutcome,
        onLiquidity = ::onLiquidity,
        onReset = ::onReset,
        onBack = navigationRouter::back,
    )

    private fun onOutcome(outcome: InvestDemoOutcome) {
        controls.setOutcome(outcome)
        isReset.update { false }
    }

    private fun onLiquidity(hasLiquidity: Boolean) {
        controls.setLiquidity(hasLiquidity)
        isReset.update { false }
    }

    private fun onReset() {
        engine.reset()
        isReset.update { true }
    }
}
