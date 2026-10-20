package co.electriccoin.zcash.ui.screen.invest.gate

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun InvestGateScreen(isChange: Boolean = false) {
    val vm = koinViewModel<InvestGateVM> { parametersOf(isChange) }
    val state by vm.state.collectAsStateWithLifecycle()
    InvestGateView(state = state)
    BackHandler { state.onBack() }
}

@Serializable
data object InvestGateArgs

/** The gate as Settings › Invest's country change. */
@Serializable
data object InvestChangeCountryArgs
