package co.electriccoin.zcash.ui.screen.swap.peer.order

import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.zapp.ZappConfirmationState
import co.electriccoin.zcash.ui.design.util.StringResource

internal data class PeerOrderState(
    val headline: StringResource,
    val supporting: StringResource?,
    val soldProgress: StringResource?,
    val rows: List<PeerOrderRow>,
    val buyers: List<PeerOrderBuyerRow>,
    /** Shown instead of an error when a read fails: the last known state, plus how old it is. */
    val lastUpdated: StringResource?,
    /**
     * The one thing worth doing next, docked beside back. Three competing buttons let the user stop
     * matching and then find withdrawal did nothing, which is the state that has to be unreachable.
     * Null when the order is settled and there is nothing left to act on.
     */
    val primaryAction: ButtonState?,
    /** A route out of a finished order, not a transaction. Rendered inline, never docked. */
    val secondaryAction: ButtonState?,
    /** The same order on Peer's own explorer. Null off production, which the explorer does not index. */
    val explorerUrl: String?,
    val confirmation: ZappConfirmationState?,
    val onBack: () -> Unit,
)

internal data class PeerOrderRow(
    val label: StringResource,
    val value: StringResource,
    val url: String? = null,
)

internal data class PeerOrderBuyerRow(
    val title: StringResource,
    val subtitle: StringResource,
    val tone: PeerBuyerTone,
    val url: String?,
)

/** A buyer who walked is neither settled nor still coming, and a boolean cannot say so. */
internal enum class PeerBuyerTone { Live, Settled, Dropped }
