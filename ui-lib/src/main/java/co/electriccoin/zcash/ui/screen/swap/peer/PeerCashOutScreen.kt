package co.electriccoin.zcash.ui.screen.swap.peer

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
internal fun PeerCashOutScreen(args: PeerCashOutArgs) {
    val vm = koinViewModel<PeerCashOutVM> { parametersOf(args.platform) }
    val state by vm.state.collectAsStateWithLifecycle()
    BackHandler { state.onBack() }
    PeerCashOutView(state = state)
}
