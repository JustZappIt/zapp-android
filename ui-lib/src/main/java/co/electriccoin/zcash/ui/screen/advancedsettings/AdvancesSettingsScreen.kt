@file:Suppress("ktlint:standard:filename")

package co.electriccoin.zcash.ui.screen.advancedsettings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.screen.chat.ChatProfileArgs
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

@Composable
internal fun AdvancedSettingsScreen() {
    val navigationRouter = koinInject<NavigationRouter>()
    LaunchedEffect(Unit) {
        navigationRouter.back()
        navigationRouter.forward(ChatProfileArgs)
    }
}

@Serializable
data object AdvancedSettingsArgs
