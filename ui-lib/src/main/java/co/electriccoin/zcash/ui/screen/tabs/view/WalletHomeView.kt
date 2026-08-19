package co.electriccoin.zcash.ui.screen.tabs.view

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.electriccoin.zcash.di.koinActivityViewModel
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.appbar.ZashiTopAppBarVM
import co.electriccoin.zcash.ui.common.repository.SwapRepository
import co.electriccoin.zcash.ui.common.usecase.EnsureSwapAssetsLoadedUseCase
import co.electriccoin.zcash.ui.design.component.zapp.ZappScreenHeader
import co.electriccoin.zcash.ui.design.component.zapp.ZappSectionLabel
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.theme.colors.ZappNavBar
import co.electriccoin.zcash.ui.screen.balances.BalanceWidgetArgs
import co.electriccoin.zcash.ui.screen.balances.BalanceWidgetVM
import co.electriccoin.zcash.ui.screen.home.HomeVM
import co.electriccoin.zcash.ui.screen.home.balancechart.BalanceChartState
import co.electriccoin.zcash.ui.screen.home.balancechart.BalanceChartVM
import co.electriccoin.zcash.ui.screen.home.migration.MigrationMessageState
import co.electriccoin.zcash.ui.screen.tabs.viewmodel.WalletSyncStateVM
import co.electriccoin.zcash.ui.screen.transactionhistory.widget.ActivityWidgetVM
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

@Composable
internal fun WalletHomeView() {
    val topAppBarVM = koinActivityViewModel<ZashiTopAppBarVM>()
    val balanceVM: BalanceWidgetVM =
        koinViewModel {
            parametersOf(
                BalanceWidgetArgs(
                    isBalanceButtonEnabled = false,
                    isExchangeRateButtonEnabled = true,
                    showDust = false,
                    isShieldBreakdownEnabled = true,
                    isBalanceBreakdownEnabled = true,
                ),
            )
        }
    val homeVM: HomeVM = koinViewModel()
    val activityVM: ActivityWidgetVM = koinViewModel()
    val chartVM: BalanceChartVM = koinViewModel()
    val syncVM: WalletSyncStateVM = koinViewModel()

    val topAppBarState by topAppBarVM.state.collectAsStateWithLifecycle()
    val balanceState by balanceVM.state.collectAsStateWithLifecycle()
    val homeState by homeVM.state.collectAsStateWithLifecycle()
    // Side-effect-only subscription. The pipeline drives sync-error + restore-success
    // navigation inside HomeVM; collecting here keeps its WhileSubscribed scope alive
    // for as long as this screen is on. Same pattern as AndroidHome.kt:37.
    homeVM.uiLifecyclePipeline.collectAsStateWithLifecycle()
    val activityState by activityVM.state.collectAsStateWithLifecycle()
    val chartState by chartVM.state.collectAsStateWithLifecycle()
    val syncChip by syncVM.state.collectAsStateWithLifecycle()

    // The send screen sources its USD figure from the 1-Click swap asset list (always on, no opt-in),
    // so the balance card reuses it for parity. Ensure the catalog is loaded even if swap was never opened.
    val swapRepository = koinInject<SwapRepository>()
    val ensureSwapAssetsLoaded = koinInject<EnsureSwapAssetsLoadedUseCase>()
    val swapAssets by swapRepository.assets.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { ensureSwapAssetsLoaded() }
    LaunchedEffect(chartState) {
        if (
            chartState is BalanceChartState.Data ||
            chartState is BalanceChartState.ZecData ||
            chartState is BalanceChartState.Empty
        ) {
            withFrameNanos { }
            BalanceChartReadinessTrace.end()
        }
    }

    val c = ZappTheme.colors

    // Shared between the headline balance and the activity rows so both present fiat- or
    // ZEC-first together; tapping the balance flips both.
    var showZecAsPrimary by rememberSaveable { mutableStateOf(false) }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(c.bg)
                .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = navBarBottom + ZappNavBar.CLEARANCE_DP.dp),
        ) {
            item {
                ZappScreenHeader(
                    title = stringResource(R.string.home_pay_title),
                    right = { SyncStatusChip(state = syncChip) },
                )
            }

            item { SyncProgressRow(state = syncChip) }

            (homeState?.message as? MigrationMessageState)?.let { migration ->
                item {
                    Spacer(Modifier.height(14.dp))
                    WalletMigrationBanner(
                        state = migration,
                        modifier = Modifier.padding(horizontal = 18.dp),
                    )
                }
            }

            item {
                Spacer(Modifier.height(14.dp))
                BalanceCard(
                    balanceState = balanceState,
                    chartState = chartState,
                    zecUsdPrice = swapAssets.zecAsset?.usdPrice,
                    showZecAsPrimary = showZecAsPrimary,
                    onToggleBalanceDisplay = { showZecAsPrimary = !showZecAsPrimary },
                    onToggleBalanceVisibility = topAppBarState.balanceVisibilityButton.onClick,
                    modifier = Modifier.padding(horizontal = 18.dp),
                )
                Spacer(Modifier.height(20.dp))
            }

            item {
                ZappSectionLabel(
                    text = stringResource(R.string.home_recent_activity_title),
                    modifier = Modifier.padding(start = 20.dp, bottom = 8.dp),
                )
            }

            activitySection(activityState, showZecAsPrimary = showZecAsPrimary)
        }

        PayActionSpeedDial(
            onPayMerchant = homeVM::onPayMerchantClick,
            onSend = { homeState?.secondButton?.onClick?.invoke() },
            onSwap = homeVM::onSwapClick,
            onReceive = { homeState?.firstButton?.onClick?.invoke() },
            onBuyUsdc = homeVM::onBuyUsdcClick,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
