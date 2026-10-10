package co.electriccoin.zcash.ui.screen.invest.receipt

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun InvestReceiptScreen(args: InvestReceiptArgs) {
    val vm = koinViewModel<InvestReceiptVM> { parametersOf(args) }
    val state by vm.state.collectAsStateWithLifecycle()
    InvestReceiptView(state = state)
    BackHandler { state.onBack() }
}

/** I11: the receipt for one Invest buy, opened from its Recent activity row. */
@Serializable
data class InvestReceiptArgs(
    val depositAddress: String,
)
