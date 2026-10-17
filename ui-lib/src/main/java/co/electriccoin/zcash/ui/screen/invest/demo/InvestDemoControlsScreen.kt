package co.electriccoin.zcash.ui.screen.invest.demo

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel

@Composable
fun InvestDemoControlsScreen() {
    val vm = koinViewModel<InvestDemoControlsVM>()
    val state by vm.state.collectAsStateWithLifecycle()
    InvestDemoControlsView(state)
    BackHandler { state.onBack() }
}

/** Settings › Invest › Demo controls, in demo builds only. */
@Serializable
data object InvestDemoControlsArgs
