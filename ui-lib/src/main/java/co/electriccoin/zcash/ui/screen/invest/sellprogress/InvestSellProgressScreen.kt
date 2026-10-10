package co.electriccoin.zcash.ui.screen.invest.sellprogress

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.electriccoin.zcash.ui.screen.invest.progress.InvestProgressView
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun InvestSellProgressScreen(args: InvestSellProgressArgs) {
    val vm = koinViewModel<InvestSellProgressVM> { parametersOf(args) }
    val state by vm.state.collectAsStateWithLifecycle()
    InvestProgressView(state = state)
    BackHandler { state.onBack() }
}

/**
 * I10: one sale's progress, identified by its deposit address. [assetId] and [usdAmount] are known when arriving
 * from the review sheet, and [assetId] when resumed from Invest home.
 */
@Serializable
data class InvestSellProgressArgs(
    val depositAddress: String,
    val assetId: String? = null,
    /** Plain decimal USD, the sale's value when it was reviewed. */
    val usdAmount: String? = null,
)
