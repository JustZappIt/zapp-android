package co.electriccoin.zcash.ui.screen.invest.settings

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel

@Composable
fun InvestSettingsScreen() {
    val vm = koinViewModel<InvestSettingsVM>()
    val state by vm.state.collectAsStateWithLifecycle()
    InvestSettingsView(state = state, onBack = vm::onBack)
    BackHandler { vm.onBack() }
}

/** S1: Settings › Invest. */
@Serializable
data object InvestSettingsArgs
