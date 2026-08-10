package co.electriccoin.zcash.ui.screen.onramp

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun OnrampScreen(args: OnrampArgs) {
    val vm = koinViewModel<OnrampVM> { parametersOf(args) }
    val state by vm.state.collectAsStateWithLifecycle()
    OnrampView(state)
    BackHandler { state.onBack() }
}

@Serializable
data class OnrampArgs(
    val currencyCode: String,
)
