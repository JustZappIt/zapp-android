package co.electriccoin.zcash.ui.screen.swap.peer.order

import kotlinx.serialization.Serializable
import xyz.justzappit.offramp.peer.PeerDepositId

/**
 * The waiting surface. Carries only the composite deposit id: everything it renders comes from one
 * indexer read, which is what lets it come back identical after process death or a reinstall.
 */
@Serializable
data class PeerOrderArgs(
    val depositIdComposite: String,
) {
    val depositId: PeerDepositId? get() = PeerDepositId.parseOrNull(depositIdComposite)
}
