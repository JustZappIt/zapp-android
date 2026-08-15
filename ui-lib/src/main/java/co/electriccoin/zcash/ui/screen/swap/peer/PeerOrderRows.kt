package co.electriccoin.zcash.ui.screen.swap.peer

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.repository.PeerCashOutRun
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.swap.peer.progress.stepLabel
import xyz.justzappit.offramp.peer.PeerCashOutStatus
import xyz.justzappit.offramp.peer.PeerOrderPhase
import xyz.justzappit.offramp.peer.PeerOrderSnapshot
import xyz.justzappit.offramp.peer.step

/**
 * One list, two sources. An order that is bridging, approving, or broadcast but not yet indexed is
 * on no indexer, so a surface that reads only the chain shows nothing at exactly the moment the user
 * most wants to look.
 */
internal fun PeerCashOutRun.rowTitle(): StringResource =
    stringRes(
        R.string.peer_offramp_in_flight_title,
        amount.toDisplayString(stripTrailingZeros = true),
        platform.displayName(),
    )

internal fun PeerCashOutRun.rowSubtitle(): StringResource =
    when {
        latest is PeerCashOutStatus.Failed -> stringRes(R.string.peer_offramp_progress_title_failed)

        // An attempt recovered from storage that nothing has picked up yet. Naming the step it was
        // seeded at would report work in progress on a run with no runner behind it.
        !isDriving && statuses.isEmpty() -> stringRes(R.string.peer_offramp_in_flight_unfinished)

        else -> stepLabel(latest.step, platform)
    }

internal fun PeerOrderSnapshot.rowTitle(): StringResource =
    stringRes(
        R.string.peer_offramp_active_order_title,
        remaining.toDisplayString(stripTrailingZeros = true),
    )

internal fun PeerOrderSnapshot.rowSubtitle(): StringResource =
    when (phase) {
        PeerOrderPhase.BUYER_PAYING -> stringRes(R.string.peer_offramp_active_order_buyer_paying)
        PeerOrderPhase.PARTLY_SOLD -> stringRes(R.string.peer_offramp_active_order_partly_sold)
        PeerOrderPhase.PAUSED -> stringRes(R.string.peer_offramp_active_order_paused)
        PeerOrderPhase.WAITING -> stringRes(R.string.peer_offramp_active_order_waiting)
        PeerOrderPhase.SOLD, PeerOrderPhase.CLOSED -> stringRes(R.string.peer_offramp_active_order_finished)
    }
