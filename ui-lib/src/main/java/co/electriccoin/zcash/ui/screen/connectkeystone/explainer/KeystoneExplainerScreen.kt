package co.electriccoin.zcash.ui.screen.connectkeystone.explainer

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import co.electriccoin.zcash.ui.NavigationRouter
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeystoneExplainerScreen() {
    val navigationRouter = koinInject<NavigationRouter>()
    KeystoneExplainerView(
        state = remember { KeystoneExplainerState(onBack = navigationRouter::back) }
    )
}

@Serializable
data object KeystoneExplainerScreenArgs
