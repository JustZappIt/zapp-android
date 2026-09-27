package co.electriccoin.zcash.ui.screen.invest.buy

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun InvestBuyScreen(args: InvestBuyArgs) {
    val vm = koinViewModel<InvestBuyVM> { parametersOf(args) }
    val state by vm.state.collectAsStateWithLifecycle()
    val review by vm.reviewState.collectAsStateWithLifecycle()
    InvestBuyView(state = state)
    InvestReviewSheet(state = review)
    BackHandler { state.onBack() }
}

/** I5: buy [assetId] (a curated 1Click asset ID), with its I6 review sheet. */
@Serializable
data class InvestBuyArgs(
    val assetId: String,
)
