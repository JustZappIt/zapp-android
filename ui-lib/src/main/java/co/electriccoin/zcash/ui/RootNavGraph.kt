package co.electriccoin.zcash.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import co.electriccoin.zcash.ui.common.compose.LocalActivity
import co.electriccoin.zcash.ui.common.migration.MigrationAppHooks
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
    val sheetStateManager = LocalSheetStateManager.current
    val flexaViewModel = koinViewModel<FlexaViewModel>()
    val navigationRouter = koinInject<NavigationRouter>()
    val applicationStateProvider = koinInject<ApplicationStateProvider>()
    val migrationAppHooks = koinInject<MigrationAppHooks>()
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
    // Only NONE -> READY is a create/restore; cold start resolves LOADING -> READY and
    // popping there discards wherever a deep link already navigated.
    var previousSecretState by remember { mutableStateOf(secretState) }
    LaunchedEffect(secretState) {
        val walletJustCreated = previousSecretState == SecretState.NONE && secretState == SecretState.READY
        previousSecretState = secretState
        if (walletJustCreated) {
            val currentRoute = navController.currentDestination?.route
            val startRoute = navController.graph.findStartDestination().route
            if (currentRoute != null && currentRoute != startRoute) {
                keyboardManager.close()
                navigationRouter.backToRoot()
            }
        }
        if (secretState == SecretState.READY) {
            // Same pattern as MainActivity.handleMigrationIntent — Home always lands on the
            // back stack first, then we redirect on top of it if a migration transfer needs
            // attention. isSyncBlocked() (fed into the synchronizer directly) already stopped
            // sync regardless of whether this redirect lands — this is routing only.
            migrationAppHooks.checkRecovery()
        }
    }
}

@Serializable
data object MainAppGraph

internal fun String?.isReceiveSheetRoute() =
    this?.startsWith("${NavigationTargets.QR_CODE}/") == true ||
        this?.startsWith("${NavigationTargets.REQUEST}/") == true
