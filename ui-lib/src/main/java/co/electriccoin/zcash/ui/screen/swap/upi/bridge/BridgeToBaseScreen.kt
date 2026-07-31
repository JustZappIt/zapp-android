package co.electriccoin.zcash.ui.screen.swap.upi.bridge

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.electriccoin.zcash.ui.design.component.zapp.ZappConfirmationBottomSheet
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Step 1 of the offramp: top up the reusable Base balance by bridging ZEC. [prefillUsdcMicro] seeds
 * the amount with the shortfall when the user arrives from a payment they couldn't yet cover; null
 * when opened directly to pre-load the balance.
 */
@Serializable
data class BridgeToBaseArgs(
    val prefillUsdcMicro: String? = null,
)

@Composable
fun BridgeToBaseScreen(args: BridgeToBaseArgs) {
    val vm = koinViewModel<BridgeToBaseVM> { parametersOf(args) }
    val state by vm.state.collectAsStateWithLifecycle()
    val leaveConfirmation by vm.leaveConfirmationState.collectAsStateWithLifecycle()
    BridgeToBaseView(state = state)
    ZappConfirmationBottomSheet(leaveConfirmation)
    BackHandler { state.onBack() }
}
