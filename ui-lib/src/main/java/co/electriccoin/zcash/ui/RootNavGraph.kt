package co.electriccoin.zcash.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import co.electriccoin.zcash.ui.common.compose.LocalActivity
import co.electriccoin.zcash.ui.common.provider.ApplicationStateProvider
import co.electriccoin.zcash.ui.common.viewmodel.SecretState
import co.electriccoin.zcash.ui.common.viewmodel.WalletViewModel
import co.electriccoin.zcash.ui.design.LocalKeyboardManager
import co.electriccoin.zcash.ui.design.LocalSheetStateManager
import co.electriccoin.zcash.ui.design.animation.ScreenAnimation.enterTransition
import co.electriccoin.zcash.ui.design.animation.ScreenAnimation.exitTransition
import co.electriccoin.zcash.ui.design.animation.ScreenAnimation.popEnterTransition
import co.electriccoin.zcash.ui.design.animation.ScreenAnimation.popExitTransition
import co.electriccoin.zcash.ui.design.animation.ScreenAnimation.sheetEnterTransition
import co.electriccoin.zcash.ui.design.animation.ScreenAnimation.sheetPopExitTransition
import co.electriccoin.zcash.ui.design.util.LocalNavController
import co.electriccoin.zcash.ui.screen.flexa.FlexaViewModel
import co.electriccoin.zcash.ui.screen.warning.viewmodel.StorageCheckViewModel
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
fun RootNavGraph(
    secretState: SecretState,
    walletViewModel: WalletViewModel,
    storageCheckViewModel: StorageCheckViewModel = koinViewModel(),
) {
    val keyboardManager = LocalKeyboardManager.current
    val flexaViewModel = koinViewModel<FlexaViewModel>()
    val navigationRouter = koinInject<NavigationRouter>()
    val sheetStateManager = LocalSheetStateManager.current
    val applicationStateProvider = koinInject<ApplicationStateProvider>()
    val navController = LocalNavController.current
    val activity = LocalActivity.current
    val navigator: Navigator =
        remember(
            activity,
            navController,
            flexaViewModel,
            keyboardManager,
            sheetStateManager,
            applicationStateProvider
        ) {
            NavigatorImpl(
                activity = activity,
                navController = navController,
                flexaViewModel = flexaViewModel,
                keyboardManager = keyboardManager,
                sheetStateManager = sheetStateManager,
                applicationStateProvider = applicationStateProvider
            )
        }

    LaunchedEffect(navigationRouter) {
        navigationRouter.observePipeline().collect {
            when (it) {
                is CustomNavigationCommand -> navigator.executeCommand(it)
                is NavigationCommand -> navigator.executeCommand(it)
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = MainAppGraph,
        modifier = Modifier.fillMaxSize(),
        enterTransition = {
            if (targetState.destination.route.isReceiveSheetRoute()) sheetEnterTransition() else enterTransition()
        },
        exitTransition = {
            if (targetState.destination.route.isReceiveSheetRoute()) ExitTransition.None else exitTransition()
        },
        popEnterTransition = {
            if (initialState.destination.route.isReceiveSheetRoute()) EnterTransition.None else popEnterTransition()
        },
        popExitTransition = {
            if (initialState.destination.route.isReceiveSheetRoute()) sheetPopExitTransition() else popExitTransition()
        }
    ) {
        this.walletNavGraph(
            storageCheckViewModel = storageCheckViewModel,
            walletViewModel = walletViewModel,
            navigationRouter = navigationRouter
        )
    }

    // Pop back to the tabs root when a wallet is created or restored from a deep
    // restore-flow screen. If we're already at the start destination (e.g. the
    // user tapped "Create new wallet" from the Wallet tab), backToRoot is a no-op.
    LaunchedEffect(secretState) {
        if (secretState == SecretState.READY) {
            val currentRoute = navController.currentDestination?.route
            val startRoute = navController.graph.findStartDestination().route
            if (currentRoute != null && currentRoute != startRoute) {
                keyboardManager.close()
                navigationRouter.backToRoot()
            }
        }
    }
}

@Serializable
data object MainAppGraph

internal fun String?.isReceiveSheetRoute() =
    this?.startsWith("${NavigationTargets.QR_CODE}/") == true ||
        this?.startsWith("${NavigationTargets.REQUEST}/") == true
