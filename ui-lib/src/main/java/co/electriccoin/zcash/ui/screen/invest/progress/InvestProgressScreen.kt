package co.electriccoin.zcash.ui.screen.invest.progress

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun InvestProgressScreen(args: InvestProgressArgs) {
    val vm = koinViewModel<InvestProgressVM> { parametersOf(args) }
    val state by vm.state.collectAsStateWithLifecycle()
    InvestProgressView(state = state)
    BackHandler { state.onBack() }
}

/** A buy resumed from Invest home has only its deposit address, so the screen words it generically. */
@Serializable
data class InvestProgressArgs(
    val depositAddress: String,
    val assetId: String? = null,
    /** Plain decimal USD. */
    val usdAmount: String? = null,
)
