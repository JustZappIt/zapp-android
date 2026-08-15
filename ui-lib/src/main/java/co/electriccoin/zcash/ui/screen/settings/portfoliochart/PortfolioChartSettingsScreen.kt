package co.electriccoin.zcash.ui.screen.settings.portfoliochart

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel

@Composable
internal fun PortfolioChartSettingsScreen() {
    val viewModel = koinViewModel<PortfolioChartSettingsVM>()
    val state by viewModel.state.collectAsStateWithLifecycle()
    BackHandler { state.onBack() }
    PortfolioChartSettingsView(state)
}

@Serializable
data object PortfolioChartSettingsArgs
