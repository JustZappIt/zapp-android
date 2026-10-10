package co.electriccoin.zcash.ui.screen.invest

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.invest.model.BuyEstimate
import co.electriccoin.zcash.ui.common.invest.model.InvestAssets
import co.electriccoin.zcash.ui.common.invest.model.PendingTrade
import co.electriccoin.zcash.ui.common.invest.provider.InvestApiException
import co.electriccoin.zcash.ui.common.invest.repository.InvestSettings
import co.electriccoin.zcash.ui.common.model.SwapAsset
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.provider.BridgeAuthorizationCancelledException
import co.electriccoin.zcash.ui.common.repository.SwapAssetsData
import co.electriccoin.zcash.ui.common.repository.SwapRepository
import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.chat.SupportChatArgs
import co.electriccoin.zcash.ui.screen.invest.buy.InvestBuyArgs
import co.electriccoin.zcash.ui.screen.invest.buy.InvestBuyState
import co.electriccoin.zcash.ui.screen.invest.buy.InvestBuyVM
import co.electriccoin.zcash.ui.screen.invest.buy.InvestReviewState
import co.electriccoin.zcash.ui.screen.invest.common.InvestCurrency
import co.electriccoin.zcash.ui.screen.invest.common.InvestCurrencyProvider
import co.electriccoin.zcash.ui.screen.invest.common.InvestFormat
import co.electriccoin.zcash.ui.screen.invest.progress.InvestProgressArgs
import co.electriccoin.zcash.ui.screen.invest.sellprogress.InvestSellProgressArgs
import co.electriccoin.zcash.ui.screen.invest.settings.InvestSettingsArgs
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.io.IOException
import java.math.BigDecimal
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("MaxLineLength")
internal abstract class InvestBuyVMTestBase {
    protected val defaultLocale = Locale.getDefault()

    @BeforeTest
    fun setUp() = Locale.setDefault(Locale.US)

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
        Locale.setDefault(defaultLocale)
    }

    protected inner class Fixture(
        val vm: InvestBuyVM,
        val repo: FakeInvestRepository,
        val router: NavigationRouter,
        private val scope: TestScope,
    ) {
        init {
            scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) { vm.state.collect {} }
            scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) { vm.reviewState.collect {} }
            scope.runCurrent()
        }

        fun now(): Instant = Instant.fromEpochMilliseconds(START_MILLIS + scope.testScheduler.currentTime)

        fun type(text: String) {
            vm.state.value.amountInput
                .onValueChange(NumberTextFieldInnerState.fromAmount(BigDecimal(text)))
        }

        fun reviewState(): InvestReviewState? = vm.reviewState.value

        fun openReview(): InvestReviewState {
            type("100")
            scope.advanceTimeBy(InvestBuyVM.AMOUNT_SETTLE_DELAY_MS + 1)
            scope.runCurrent()
            val state: InvestBuyState = vm.state.value
            state.primaryButton.onClick()
            scope.runCurrent()
            return assertNotNull(vm.reviewState.value)
        }
    }

    protected val follower = FakeInvestTradeFollower()

    protected fun TestScope.fixture(
        currency: InvestCurrencyProvider = USD_CURRENCY,
        clock: Clock = virtualClock(),
        settings: InvestSettings = INVEST_READY,
    ): Fixture {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeInvestRepository()
        val router = mockk<NavigationRouter>(relaxed = true)
        val account =
            mockk<WalletAccount>().also { every { it.spendableShieldedBalance } returns Zatoshi(100_000_000L) }
        val accounts =
            mockk<AccountDataSource>().also { every { it.selectedAccount } returns flowOf(account) }
        val swap =
            mockk<SwapRepository>().also {
                every { it.assets } returns
                    MutableStateFlow(
                        SwapAssetsData(
                            zecAsset =
                                mockk<SwapAsset>(relaxed = true) {
                                    every { usdPrice } returns BigDecimal(50)
                                },
                        ),
                    )
            }
        val vm =
            InvestBuyVM(
                args = InvestBuyArgs(NVIDIA.assetId),
                investRepository = repo,
                settingsRepository = FakeInvestSettingsRepository(settings),
                accountDataSource = accounts,
                swapRepository = swap,
                currencyProvider = currency,
                tradeFollower = follower,
                navigationRouter = router,
                clock = clock,
            )
        return Fixture(vm, repo, router, this)
    }

    protected companion object {
        val EXPECTED_ROUTE =
            InvestSellProgressArgs("0xsale", InvestAssets.curated.first { it.ticker == "NVDA" }.assetId)
        val NVIDIA = InvestAssets.curated.first { it.ticker == "NVDA" }
        val PRICED =
            BuyEstimate.Priced(
                zecIn = BigDecimal("0.06478"),
                unitsOut = BigDecimal("0.4410"),
                usdOut = BigDecimal("99.01"),
                feesUsd = BigDecimal("0.95"),
                etaSeconds = 470,
                refundFeeZec = BigDecimal("0.00032"),
            )
    }
}
