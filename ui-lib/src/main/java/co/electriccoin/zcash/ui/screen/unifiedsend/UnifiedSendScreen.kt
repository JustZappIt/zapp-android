package co.electriccoin.zcash.ui.screen.unifiedsend

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.toRoute
import co.electriccoin.zcash.ui.screen.balances.BalanceWidgetArgs
import co.electriccoin.zcash.ui.screen.balances.BalanceWidgetVM
import co.electriccoin.zcash.ui.screen.swap.SwapCancelView
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
internal fun UnifiedSendScreen(args: UnifiedSendArgs) {
    val vm = koinViewModel<UnifiedSendVM> { parametersOf(args) }
    val balanceVM =
        koinViewModel<BalanceWidgetVM> {
            parametersOf(
                BalanceWidgetArgs(
                    isBalanceButtonEnabled = true,
                    isExchangeRateButtonEnabled = true,
                    showDust = true
                )
            )
        }
    val state by vm.state.collectAsStateWithLifecycle()
    val balanceState by balanceVM.state.collectAsStateWithLifecycle()
    val cancelState by vm.cancelState.collectAsStateWithLifecycle()
    state?.let { UnifiedSendView(it, balanceState) }
    BackHandler(cancelState == null) { vm.onBack() }
    SwapCancelView(cancelState)
}

@Serializable
data class UnifiedSendArgs(
    val recipientAddress: String? = null,
    val recipientAddressType: String? = null,
    val isScanZip321Enabled: Boolean = true,
)
