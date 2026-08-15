package co.electriccoin.zcash.ui.screen.swap.peer

import kotlinx.serialization.Serializable
import xyz.justzappit.offramp.peer.PeerPlatform

@Serializable
data class PeerCashOutArgs(
    val platformWireName: String = PeerPlatform.REVOLUT.wireName,
) {
    val platform: PeerPlatform
        get() = PeerPlatform.fromWireNameOrNull(platformWireName) ?: PeerPlatform.REVOLUT
}
