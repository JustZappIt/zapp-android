package co.electriccoin.zcash.ui.screen.invest.sell

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun InvestSellScreen(args: InvestSellArgs) {
    val vm = koinViewModel<InvestSellVM> { parametersOf(args) }
    val state by vm.state.collectAsStateWithLifecycle()
    val review by vm.reviewState.collectAsStateWithLifecycle()
    InvestSellView(state = state)
    InvestSellReviewSheet(state = review)
    BackHandler { state.onBack() }
}

/** I8: sell some or all of the holding in [assetId] (a curated 1Click asset ID), with its I9 review sheet. */
@Serializable
data class InvestSellArgs(
    val assetId: String,
)
