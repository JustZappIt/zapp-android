package co.electriccoin.zcash.ui.screen.resync.confirm

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.electriccoin.zcash.ui.screen.common.LceRenderer
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel

@Composable
fun ResyncConfirmScreen() {
    val vm = koinViewModel<ResyncConfirmVM>()
    val state by vm.state.collectAsStateWithLifecycle()
    LceRenderer(state) { ResyncConfirmView(it) }
}

@Serializable
data object ResyncConfirmArgs
