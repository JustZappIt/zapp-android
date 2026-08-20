package co.electriccoin.zcash.ui.screen.swap.info

import androidx.compose.runtime.Composable
import co.electriccoin.zcash.ui.NavigationRouter
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

@Composable
fun CrossPayInfoScreen() {
    val navigationRouter = koinInject<NavigationRouter>()
    val state =
        CrossPayInfoState(
            onBack = { navigationRouter.back() }
        )
    CrossPayInfoView(state)
}

@Serializable
data object CrossPayInfoArgs
