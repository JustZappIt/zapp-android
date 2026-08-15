package co.electriccoin.zcash.ui.screen.swap.peer.progress

import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.zapp.ZappStep
import co.electriccoin.zcash.ui.design.util.StringResource

internal data class PeerCashOutOrderSummary(
    val amountUsdcDisplay: StringResource,
    val platform: StringResource,
    val currencies: StringResource,
)

internal data class PeerCashOutFailureCard(
    val stepLabel: StringResource,
    val reason: StringResource,
    val txHash: String?,
    val txExplorerUrl: String?,
    /** Absent for the unknown-outcome codes: a second attempt is how one deposit becomes two. */
    val retry: ButtonState?,
)

internal data class PeerCashOutProgressState(
    val title: StringResource,
    val subtitle: StringResource?,
    val summary: PeerCashOutOrderSummary?,
    val steps: List<ZappStep>,
    val failure: PeerCashOutFailureCard?,
    val primaryButton: ButtonState?,
    val isOrderLive: Boolean = false,
    val onBack: () -> Unit,
)
