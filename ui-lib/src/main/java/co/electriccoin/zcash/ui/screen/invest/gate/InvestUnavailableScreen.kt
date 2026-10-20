package co.electriccoin.zcash.ui.screen.invest.gate

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun InvestUnavailableScreen(args: InvestUnavailableArgs) {
    val vm = koinViewModel<InvestUnavailableVM> { parametersOf(args) }
    InvestUnavailableView(state = vm.state)
    BackHandler { vm.state.onDone() }
}

@Serializable
data class InvestUnavailableArgs(
    val countryCode: String,
)
