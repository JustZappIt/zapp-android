package co.electriccoin.zcash.ui.screen.swap.peer.order

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.design.component.zapp.ZappConfirmationBottomSheet
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

@Composable
internal fun PeerOrderScreen(args: PeerOrderArgs) {
    val depositId = args.depositId
    if (depositId == null) {
        // A malformed id cannot address an order, and there is nothing to wait for. Routed from an
        // effect rather than the composition body, which runs again on every recomposition.
        val router = koinInject<NavigationRouter>()
        LaunchedEffect(Unit) { router.back() }
        return
    }
    val vm = koinViewModel<PeerOrderVM> { parametersOf(depositId) }
    val state by vm.state.collectAsStateWithLifecycle()
    // Back is safe here by design: the order lives on chain and leaving never cancels it.
    BackHandler { state.onBack() }
    PeerOrderView(state = state)
    ZappConfirmationBottomSheet(state.confirmation)
}
