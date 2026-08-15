package co.electriccoin.zcash.ui.screen.swap.peer

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import xyz.justzappit.offramp.peer.PeerPlatform

internal fun PeerPlatform.displayName(): StringResource =
    when (this) {
        PeerPlatform.REVOLUT -> stringRes(R.string.settings_p2p_rail_revolut)
        PeerPlatform.ZELLE -> stringRes(R.string.settings_p2p_rail_zelle)
        PeerPlatform.CHIME -> stringRes(R.string.settings_p2p_rail_chime)
        PeerPlatform.MONZO -> stringRes(R.string.settings_p2p_rail_monzo)
    }

/** Peer's own capability hints, which are the field copy a user needs to type the right thing. */
internal fun PeerPlatform.handleHint(): StringResource =
    when (this) {
        PeerPlatform.REVOLUT -> stringRes(R.string.peer_offramp_hint_revolut)
        PeerPlatform.ZELLE -> stringRes(R.string.peer_offramp_hint_zelle)
        PeerPlatform.CHIME -> stringRes(R.string.peer_offramp_hint_chime)
        PeerPlatform.MONZO -> stringRes(R.string.peer_offramp_hint_monzo)
    }
