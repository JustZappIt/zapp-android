package co.electriccoin.zcash.ui.common.invest.demo

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How the next demo buy or sale ends. */
enum class InvestDemoOutcome {
    /** The buy is held / the sale's ZEC is sent. */
    COMPLETES,

    /** The buy's ZEC comes back / the stock returns to the account. */
    REFUNDED,

    /** 1Click reports FAILED, so the trade waits for support until dismissed. */
    NEEDS_ATTENTION,
}

/**
 * The knobs the demo build's Settings › Invest › Demo controls turn, so every branch of the flow can be shown
 * without waiting for a real market to misbehave. Lives as long as the app; nothing is saved.
 */
class InvestDemoControls {
    private val _outcome = MutableStateFlow(InvestDemoOutcome.COMPLETES)
    val outcome: StateFlow<InvestDemoOutcome> = _outcome.asStateFlow()

    private val _hasLiquidity = MutableStateFlow(true)

    /** False makes every quote answer "No liquidity", as 1Click does on a quiet weekend. */
    val hasLiquidity: StateFlow<Boolean> = _hasLiquidity.asStateFlow()

    fun setOutcome(outcome: InvestDemoOutcome) {
        _outcome.value = outcome
    }

    fun setLiquidity(hasLiquidity: Boolean) {
        _hasLiquidity.value = hasLiquidity
    }
}
