package co.electriccoin.zcash.ui.screen.resync.height

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.electriccoin.zcash.di.koinActivityViewModel
import co.electriccoin.zcash.ui.screen.common.BlockHeightView
import co.electriccoin.zcash.ui.screen.common.LceRenderer
import kotlinx.serialization.Serializable

@Composable
fun ResyncHeightScreen() {
    val viewModel = koinActivityViewModel<ResyncHeightVM>()
    val state by viewModel.state.collectAsStateWithLifecycle()
    LceRenderer(state) { BlockHeightView(it) }
}

@Serializable
data object ResyncHeightArgs
