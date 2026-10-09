package co.electriccoin.zcash.ui.screen.swap.peer

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.zapp.ZappInfoSheet
import co.electriccoin.zcash.ui.design.util.getValue
import xyz.justzappit.offramp.peer.PeerPlatform

@Composable
internal fun PeerCashOutInfoSheet(platform: PeerPlatform, onDismiss: () -> Unit) {
    ZappInfoSheet(
        title = stringResource(R.string.peer_offramp_info_title),
        steps =
            listOf(
                stringResource(R.string.peer_offramp_info_step_offer),
                stringResource(R.string.peer_offramp_info_step_buyer, platform.displayName().getValue()),
                stringResource(R.string.peer_offramp_info_step_release),
            ),
        notes =
            listOf(
                stringResource(R.string.peer_offramp_info_note_final),
                stringResource(R.string.peer_offramp_info_note_rate),
                stringResource(R.string.peer_offramp_info_note_open),
                stringResource(R.string.peer_offramp_info_note_funds),
            ),
        onDismiss = onDismiss,
    )
}
