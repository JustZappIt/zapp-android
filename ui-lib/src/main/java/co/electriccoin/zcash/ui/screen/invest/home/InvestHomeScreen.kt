package co.electriccoin.zcash.ui.screen.invest.home

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel

@Composable
fun InvestHomeScreen() {
    val vm = koinViewModel<InvestHomeVM>()
    val state by vm.state.collectAsStateWithLifecycle()
    InvestHomeView(state = state)
    BackHandler { state.onBack() }
}

/** I3: Invest home. */
@Serializable
data object InvestHomeArgs
