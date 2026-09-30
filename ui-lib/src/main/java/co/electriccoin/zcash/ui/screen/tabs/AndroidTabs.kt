package co.electriccoin.zcash.ui.screen.tabs

import androidx.compose.runtime.Composable
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.screen.tabs.view.ZappTabsScaffold
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

@Serializable
object TabsArgs

@Composable
fun AndroidTabs() {
    val navigationRouter = koinInject<NavigationRouter>()
    ZappTabsScaffold(navigationRouter = navigationRouter)
}
