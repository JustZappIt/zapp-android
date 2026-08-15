package co.electriccoin.zcash.ui.screen.settings.p2p

import androidx.annotation.DrawableRes
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.P2pProvider
import xyz.justzappit.offramp.peer.PeerPlatform

@DrawableRes
internal fun P2pProvider.logo(): Int =
    when (this) {
        P2pProvider.P2P_ME -> R.drawable.ic_p2p_logo
        P2pProvider.PEER -> R.drawable.ic_provider_peer
    }

@DrawableRes
internal fun PeerPlatform.logo(): Int =
    when (this) {
        PeerPlatform.REVOLUT -> R.drawable.ic_rail_revolut
        PeerPlatform.ZELLE -> R.drawable.ic_rail_zelle
        PeerPlatform.CHIME -> R.drawable.ic_rail_chime
        PeerPlatform.MONZO -> R.drawable.ic_rail_monzo
    }
