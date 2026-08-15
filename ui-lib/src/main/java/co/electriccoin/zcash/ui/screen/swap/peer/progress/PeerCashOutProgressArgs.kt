package co.electriccoin.zcash.ui.screen.swap.peer.progress

import kotlinx.serialization.Serializable

/**
 * Addresses a run rather than describing one. A description can disagree with the checkpoint it is
 * matched against, and the checkpoint wins without saying so; a handle cannot disagree with itself.
 */
@Serializable
data class PeerCashOutProgressArgs(
    val cashOutId: String,
)
