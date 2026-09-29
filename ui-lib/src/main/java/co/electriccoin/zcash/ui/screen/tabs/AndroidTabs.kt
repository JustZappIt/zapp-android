package co.electriccoin.zcash.ui.screen.tabs

import androidx.compose.runtime.Composable
import androidx.lifecycle.SavedStateHandle
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.screen.tabs.view.ZappTabsScaffold
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

@Serializable
object TabsArgs

internal const val SELECTED_TAB_KEY = "selected_tab"

@Composable
fun AndroidTabs(tabState: SavedStateHandle) {
    val navigationRouter = koinInject<NavigationRouter>()
    ZappTabsScaffold(navigationRouter = navigationRouter, tabState = tabState)
}
