package co.electriccoin.zcash.ui.screen.connectkeystone.connected

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import co.electriccoin.zcash.ui.NavigationRouter
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

@Composable
fun KeystoneConnectedScreen() {
    val navigationRouter = koinInject<NavigationRouter>()
    BackHandler { /* consume back - user must use the button */ }
    KeystoneConnectedView(
        state =
            KeystoneConnectedState(
                onClose = { navigationRouter.backToRoot() }
            )
    )
}

@Serializable
data object KeystoneConnectedArgs
