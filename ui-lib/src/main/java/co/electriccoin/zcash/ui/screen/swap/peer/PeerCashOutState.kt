package co.electriccoin.zcash.ui.screen.swap.peer

import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldState
import co.electriccoin.zcash.ui.design.component.TextFieldState
import co.electriccoin.zcash.ui.design.util.StringResource
import xyz.justzappit.offramp.peer.PeerCurrency
import xyz.justzappit.offramp.peer.PeerPlatform

internal data class PeerCashOutState(
    val platform: PeerPlatform,
    val title: StringResource,
    val amountInput: NumberTextFieldState,
    val amountError: StringResource?,
    /** Shown against the right edge of the amount field, never below it. */
    val availableBalance: StringResource,
    /** The typed amount in the primary currency, the way the UPI offramp shows its USDC equivalent. */
    val fiatEquivalent: StringResource?,
    /** Rate, typical wait and what is spendable, as labelled rows rather than prose. */
    val ledger: List<PeerLedgerRow>,
    /** Shown inside the ledger so an error never displaces the amount field above it. */
    val notice: StringResource?,
    val isNoticeDanger: Boolean,
    val topUpButton: ButtonState,
    val handleField: TextFieldState,
    val handleHint: StringResource,
    /** Set only where normalising changed what was typed. */
    val handleNormalized: StringResource?,
    /** Set on the rails the curator cannot check. */
    val handleUnverified: StringResource?,
    val currencies: List<PeerCurrencyChipState>,
    val activeOrders: List<PeerActiveOrderState>,
    val primaryButton: ButtonState,
    val onBack: () -> Unit,
)

internal data class PeerLedgerRow(
    val label: StringResource,
    val value: StringResource,
)

internal data class PeerCurrencyChipState(
    val currency: PeerCurrency,
    val isSelected: Boolean,
    val isToggleable: Boolean,
    val onClick: () -> Unit,
)

internal data class PeerActiveOrderState(
    val key: String,
    val title: StringResource,
    val subtitle: StringResource,
    val onClick: () -> Unit,
)
