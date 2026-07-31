package co.electriccoin.zcash.ui.screen.balances

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.wallet.ExchangeRateState

data class BalanceWidgetState(
    val showDust: Boolean,
    val totalBalance: Zatoshi,
    val button: BalanceButtonState?,
    val exchangeRate: ExchangeRateState?,
    val onAddZec: (() -> Unit)? = null,
    val breakdown: ShieldBreakdownState? = null,
    /** Opens the per-pool breakdown sheet; `null` hides the affordance. */
    val onBalanceClick: (() -> Unit)? = null,
)

data class ShieldBreakdownState(
    val shieldedBalance: Zatoshi,
    val transparentBalance: Zatoshi,
    val onShieldClick: () -> Unit,
    val onBreakdownClick: () -> Unit,
)
