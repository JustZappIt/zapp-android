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

/** I1: the one-time country-of-residence gate. */
@Serializable
data object InvestGateArgs

/** Settings › Invest › Country of residence: the gate, saving back to Settings. */
@Serializable
data object InvestChangeCountryArgs
