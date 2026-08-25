package co.electriccoin.zcash.ui.screen.unifiedsend

import androidx.navigation.NavBackStackEntry
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.BaseNavigationCommand
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.DynamicSwapAsset
import co.electriccoin.zcash.ui.common.model.SwapAsset
import co.electriccoin.zcash.ui.common.model.SwapBlockchain
import co.electriccoin.zcash.ui.common.model.SwapMode
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.ZecSwapAsset
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.repository.ExchangeRateRepository
import co.electriccoin.zcash.ui.common.repository.SwapAssetsData
import co.electriccoin.zcash.ui.common.repository.SwapRepository
import co.electriccoin.zcash.ui.common.usecase.CancelSwapUseCase
import co.electriccoin.zcash.ui.common.usecase.CreateProposalUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSelectedSwapAssetUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSlippageUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSwapAssetsUseCase
import co.electriccoin.zcash.ui.common.usecase.IsABContactHintVisibleUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToScanGenericAddressUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToSelectABSwapRecipientUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToSelectRecipientUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToSwapQuoteIfAvailableUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveABContactPickedUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveClearSendUseCase
import co.electriccoin.zcash.ui.common.usecase.PrefillSendUseCase
import co.electriccoin.zcash.ui.common.usecase.PreselectSwapAssetUseCase
import co.electriccoin.zcash.ui.common.usecase.RequestSwapQuoteUseCase
import co.electriccoin.zcash.ui.common.usecase.ValidateAddressUseCase
import co.electriccoin.zcash.ui.common.wallet.ExchangeRateState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.imageRes
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.swap.info.CrossPayInfoArgs
import co.electriccoin.zcash.ui.screen.swap.slippage.SwapSlippageArgs
import io.ktor.client.network.sockets.SocketTimeoutException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import kotlin.reflect.KClass
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Which side of the form the user last typed into decides whether the quote is exact-input or
 * exact-output, so these cover the flip in both directions and everything the exact-output branch
 * does differently: the amount it submits, the balance it checks, and the copy it asks for.
 *
 * Prices are fixed at BTC $50,000 and ZEC $50, so 1 ZEC ≙ 0.001 BTC and the arithmetic in the
 * assertions stays readable.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UnifiedSendVMTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `the form starts in exact input with the destination amount shown as an estimate`() =
        swapForm {
            vm.onZecAmountChange(amount("1"))
            runCurrent()

            assertNull(state().payEstimate)
            val theyReceive = assertNotNull(state().theyReceive)
            assertEquals(stringRes(R.string.unified_send_they_receive_approx), theyReceive.label)
            assertEquals("BTC", theyReceive.unit)
            assertEqualsBd("0.001", theyReceive.amount.innerState.amount)
            // The USD side is one tap away, not a second field competing for the same row.
            assertNotNull(theyReceive.onSwapCurrency)
        }

    @Test
    fun `typing a destination amount pins the quote to it and turns the pay side into an estimate`() =
        swapForm {
            vm.onZecAmountChange(amount("1"))
            vm.onTokenAmountChange(amount("0.002"))
            runCurrent()

            assertNotNull(state().payEstimate)
            val theyReceive = assertNotNull(state().theyReceive)
            assertEquals(stringRes(R.string.unified_send_they_receive_exact), theyReceive.label)
            assertEqualsBd("0.002", theyReceive.amount.innerState.amount)
            assertEquals("BTC", theyReceive.unit)
        }

    @Test
    fun `emptying the destination field leaves the payment exact output with nothing to submit`() =
        swapForm {
            vm.onAddressChange(BTC_ADDRESS)
            vm.onZecAmountChange(amount("1"))
            vm.onTokenAmountChange(amount("0.002"))
            runCurrent()
            assertNotNull(state().payEstimate)

            vm.onTokenAmountChange(NumberTextFieldInnerState())
            runCurrent()

            // The estimate must not flood back into the field the user just cleared — it would put
            // a number they never typed under the caret and the next keystroke would extend it.
            assertNotNull(state().payEstimate)
            val theyReceive = assertNotNull(state().theyReceive)
            assertEquals(stringRes(R.string.unified_send_they_receive_exact), theyReceive.label)
            assertNull(theyReceive.amount.innerState.amount)
            assertEquals(PrimaryButtonState.Disabled, state().primaryButton)
        }

    @Test
    fun `while exact input the destination field pre-selects, so typing replaces the estimate`() =
        swapForm {
            vm.onZecAmountChange(amount("1"))
            runCurrent()

            val theyReceive = assertNotNull(state().theyReceive)
            assertEquals(SELECT_ALL, theyReceive.amount.innerState.innerTextFieldState.selection)
        }

    @Test
    fun `merely putting the caret in the estimate does not pin the payment to the recipient`() =
        swapForm {
            vm.onZecAmountChange(amount("1"))
            runCurrent()
            val estimate = assertNotNull(state().theyReceive).amount

            // The text field reports a bare selection change as a value change, forwarding the
            // estimate untouched. That is a tap, not an entry.
            estimate.onValueChange(estimate.innerState)
            runCurrent()

            assertNull(state().payEstimate)
            assertEquals(
                stringRes(R.string.unified_send_they_receive_approx),
                assertNotNull(state().theyReceive).label
            )
        }

    @Test
    fun `clearing the estimate does count as entry, so the field becomes the user's`() =
        swapForm {
            vm.onZecAmountChange(amount("1"))
            runCurrent()
            val estimate = assertNotNull(state().theyReceive).amount

            estimate.onValueChange(NumberTextFieldInnerState())
            runCurrent()

            assertNotNull(state().payEstimate)
            assertNull(assertNotNull(state().theyReceive).amount.innerState.amount)
        }

    @Test
    fun `a USD-entered amount is stored as the figure the field shows, not a longer one`() =
        swapForm(asset = btc(usdPrice = BigDecimal("47123"))) {
            // $100 at $47,123 is 0.0021221...; the field renders 0.00212, so that is what we quote.
            vm.onTokenFiatAmountChange(amount("100"))
            runCurrent()

            assertEqualsBd("0.00212", assertNotNull(state().theyReceive).amount.innerState.amount)
        }

    @Test
    fun `the destination shows one figure at a time and the toggle swaps which`() =
        swapForm {
            vm.onTokenAmountChange(amount("0.002"))
            runCurrent()
            assertEquals("BTC", assertNotNull(state().theyReceive).unit)
            assertEqualsBd("0.002", assertNotNull(state().theyReceive).amount.innerState.amount)

            assertNotNull(assertNotNull(state().theyReceive).onSwapCurrency).invoke()
            runCurrent()

            // Same amount, other denomination — never both on screen at once.
            val theyReceive = assertNotNull(state().theyReceive)
            assertEquals("USD", theyReceive.unit)
            assertEqualsBd("100", theyReceive.amount.innerState.amount)
        }

    @Test
    fun `an asset with no USD price offers no toggle and stays on the token`() =
        swapForm(asset = pricelessToken()) {
            vm.onTokenAmountChange(amount("5"))
            runCurrent()

            val theyReceive = assertNotNull(state().theyReceive)
            assertEquals("XYZ", theyReceive.unit)
            assertNull(theyReceive.onSwapCurrency)
        }

    @Test
    fun `typing a USD amount pins the payment to the destination amount it buys`() =
        swapForm {
            vm.onAddressChange(BTC_ADDRESS)
            vm.onTokenFiatAmountChange(amount("100"))
            runCurrent()

            // $100 of BTC at $50,000 is 0.002 BTC, and that is what gets quoted.
            assertEqualsBd("0.002", assertNotNull(state().theyReceive).amount.innerState.amount)
            assertNotNull(state().payEstimate)

            assertIs<PrimaryButtonState.Review>(state().primaryButton).onClick()
            runCurrent()

            coVerify(exactly = 1) {
                requestSwapQuote.requestExactOutput(BigDecimal("0.002"), BTC_ADDRESS, any())
            }
        }

    @Test
    fun `a USD amount is converted down to what the destination chain can settle`() =
        swapForm(asset = btc(decimals = 6)) {
            // $1 of BTC at $50,000 is 0.00002 BTC; at 6 decimals the rest cannot be delivered.
            vm.onTokenFiatAmountChange(amount("1.0000001"))
            runCurrent()

            assertEqualsBd("0.00002", assertNotNull(state().theyReceive).amount.innerState.amount)
        }

    @Test
    fun `the destination fields disable while a quote is in flight, slippage included`() =
        swapForm {
            coEvery { requestSwapQuote.requestExactOutput(any(), any(), any()) } coAnswers { awaitCancellation() }
            vm.onAddressChange(BTC_ADDRESS)
            vm.onTokenAmountChange(amount("0.002"))
            runCurrent()

            assertIs<PrimaryButtonState.Review>(state().primaryButton).onClick()
            runCurrent()

            // The repository re-reads the tolerance when it builds the request, so letting it change
            // now would quote something other than what the form is showing.
            assertEquals(false, assertNotNull(state().slippage).isEnabled)
            val theyReceive = assertNotNull(state().theyReceive)
            assertEquals(false, theyReceive.amount.isEnabled)
            assertNull(theyReceive.onSwapCurrency)
            assertNull(assertNotNull(state().payEstimate).onClick)
        }

    @Test
    fun `a failed asset load offers a retry, not a review`() =
        swapForm(assetsError = SocketTimeoutException()) {
            runCurrent()

            // One refresh already went out when the form opened.
            verify(exactly = 1) { swapRepository.requestRefreshAssets() }

            assertIs<PrimaryButtonState.Retry>(state().primaryButton).onClick()

            verify(exactly = 2) { swapRepository.requestRefreshAssets() }
        }

    @Test
    fun `the slippage sheet drops the USD figure when the ZEC price is unknown`() =
        swapForm(zecPrice = null) {
            vm.onZecAmountChange(amount("1"))
            runCurrent()

            assertNotNull(state().slippage).onClick()

            // Better no figure than a confident "US$0.00".
            assertNull(assertIs<SwapSlippageArgs>(navigationRouter.forwarded.single()).fiatAmount)
        }

    @Test
    fun `tapping the pay estimate returns to exact input carrying the estimate over`() =
        swapForm {
            vm.onTokenAmountChange(amount("0.002"))
            runCurrent()

            assertNotNull(assertNotNull(state().payEstimate).onClick).invoke()
            runCurrent()

            assertNull(state().payEstimate)
            // The 2 ZEC the exact-output leg would have cost is now the typed pay amount.
            assertEqualsBd("2", state().zecAmount.innerState.amount)
        }

    @Test
    fun `an exact output payment submits the destination amount, not the ZEC estimate`() =
        swapForm {
            vm.onAddressChange(BTC_ADDRESS)
            vm.onTokenAmountChange(amount("0.002"))
            runCurrent()

            assertIs<PrimaryButtonState.Review>(state().primaryButton).onClick()
            runCurrent()

            coVerify(exactly = 1) {
                requestSwapQuote.requestExactOutput(BigDecimal("0.002"), BTC_ADDRESS, any())
            }
            coVerify(exactly = 0) { requestSwapQuote.requestExactInput(any(), any(), any()) }
        }

    @Test
    fun `an exact input payment still submits the typed ZEC amount`() =
        swapForm {
            vm.onAddressChange(BTC_ADDRESS)
            vm.onZecAmountChange(amount("1"))
            runCurrent()

            assertIs<PrimaryButtonState.Review>(state().primaryButton).onClick()
            runCurrent()

            coVerify(exactly = 1) { requestSwapQuote.requestExactInput(BigDecimal("1"), BTC_ADDRESS, any()) }
            coVerify(exactly = 0) { requestSwapQuote.requestExactOutput(any(), any(), any()) }
        }

    @Test
    fun `the exact output balance check runs against the estimated ZEC cost`() =
        swapForm(spendable = Zatoshi(150_000_000)) {
            // 0.002 BTC costs an estimated 2 ZEC — more than the 1.5 ZEC available.
            vm.onTokenAmountChange(amount("0.002"))
            runCurrent()

            assertNotNull(state().amountError)
            assertIs<PrimaryButtonState.TopUp>(state().primaryButton)
        }

    @Test
    fun `an exact output payment inside the balance is allowed`() =
        swapForm(spendable = Zatoshi(300_000_000)) {
            vm.onTokenAmountChange(amount("0.002"))
            runCurrent()

            assertNull(state().amountError)
        }

    @Test
    fun `a destination amount finer than the asset can settle is rejected`() =
        swapForm(asset = btc(decimals = 6)) {
            vm.onTokenAmountChange(amount("0.001"))
            vm.onTokenAmountChange(amount("0.0012345678"))
            runCurrent()

            assertEqualsBd("0.001", assertNotNull(state().theyReceive).amount.innerState.amount)
        }

    @Test
    fun `changing the selected asset clears the destination amount`() =
        swapForm {
            vm.onTokenAmountChange(amount("0.002"))
            runCurrent()
            assertNotNull(state().payEstimate)

            selectedAsset.value = usdc()
            runCurrent()

            assertNull(state().payEstimate)
            assertNull(assertNotNull(state().theyReceive).amount.innerState.amount)
        }

    @Test
    fun `the slippage sheet is told the mode and the USD value of the recipient's amount`() =
        swapForm {
            vm.onTokenAmountChange(amount("0.002"))
            runCurrent()

            assertNotNull(state().slippage).onClick()

            val args = assertIs<SwapSlippageArgs>(navigationRouter.forwarded.single())
            assertEquals(SwapMode.EXACT_OUTPUT, args.mode)
            assertEqualsBd("100", BigDecimal(assertNotNull(args.fiatAmount)))
        }

    @Test
    fun `the slippage sheet stays on exact input while the pay side is authoritative`() =
        swapForm {
            vm.onZecAmountChange(amount("1"))
            runCurrent()

            assertNotNull(state().slippage).onClick()

            val args = assertIs<SwapSlippageArgs>(navigationRouter.forwarded.single())
            assertEquals(SwapMode.EXACT_INPUT, args.mode)
            assertEqualsBd("50", BigDecimal(assertNotNull(args.fiatAmount)))
        }

    @Test
    fun `a ZEC-direct send has no destination row, no estimate and no CrossPay explainer`() =
        swapForm(asset = zec()) {
            vm.onTokenAmountChange(amount("0.002"))
            runCurrent()

            assertNull(state().theyReceive)
            assertNull(state().payEstimate)
            assertNull(state().infoButton)
        }

    @Test
    fun `the CrossPay explainer is offered while swapping`() =
        swapForm {
            runCurrent()

            assertNotNull(state().infoButton).onClick()

            assertEquals(CrossPayInfoArgs, navigationRouter.forwarded.single())
        }

    // region harness

    private fun swapForm(
        asset: SwapAsset = btc(),
        spendable: Zatoshi = Zatoshi(1_000_000_000),
        zecPrice: BigDecimal? = BigDecimal("50"),
        assetsError: Exception? = null,
        block: suspend Harness.() -> Unit
    ) = runTest {
        val harness = Harness(this, asset, spendable, zecPrice, assetsError)
        val collection = backgroundScope.launch { harness.vm.state.collect() }
        runCurrent()
        harness.block()
        collection.cancel()
    }

    private class Harness(
        private val scope: TestScope,
        asset: SwapAsset,
        spendable: Zatoshi,
        zecPrice: BigDecimal?,
        assetsError: Exception?,
    ) {
        val selectedAsset = MutableStateFlow<SwapAsset?>(asset)
        val requestSwapQuote = mockk<RequestSwapQuoteUseCase>(relaxed = true)
        val navigationRouter = RecordingNavigationRouter()

        private val assetsData =
            MutableStateFlow(
                SwapAssetsData(
                    data = listOf(asset).takeIf { assetsError == null },
                    zecAsset = zecAsset(zecPrice),
                    error = assetsError
                )
            )
        private val slippageFlow = MutableStateFlow(BigDecimal.ONE)

        val swapRepository =
            mockk<SwapRepository>(relaxed = true) {
                every { assets } returns assetsData
                every { slippage } returns slippageFlow
            }

        // Event buses the form listens to but these tests never publish on. Real instances, since
        // an unfed bus is already the silence these cases want and needs no stubbing.
        private val prefillSend = PrefillSendUseCase()
        private val observeClearSend = ObserveClearSendUseCase()
        private val observeABContactPicked = ObserveABContactPickedUseCase(mockk<SynchronizerProvider>(relaxed = true))

        val vm =
            UnifiedSendVM(
                args = UnifiedSendArgs(),
                mapper = UnifiedSendVMMapper(),
                getSelectedSwapAsset =
                    mockk<GetSelectedSwapAssetUseCase> { every { observe() } returns selectedAsset },
                getSwapAssetsUseCase = mockk<GetSwapAssetsUseCase> { every { observe() } returns assetsData },
                getSlippage = mockk<GetSlippageUseCase> { every { observe() } returns slippageFlow },
                getSelectedWalletAccount =
                    mockk<GetSelectedWalletAccountUseCase> {
                        every { observe() } returns
                            MutableStateFlow<WalletAccount?>(
                                mockk(relaxed = true) {
                                    every { spendableShieldedBalance } returns spendable
                                }
                            )
                    },
                preselectSwapAsset =
                    mockk<PreselectSwapAssetUseCase> { every { observe() } returns emptyFlow<Unit>() },
                swapRepository = swapRepository,
                cancelSwap = mockk(relaxed = true),
                requestSwapQuote = requestSwapQuote,
                navigateToSwapQuoteIfAvailable = mockk<NavigateToSwapQuoteIfAvailableUseCase>(relaxed = true),
                validateAddress = mockk<ValidateAddressUseCase>(relaxed = true),
                createProposal = mockk<CreateProposalUseCase>(relaxed = true),
                observeABContactPicked = observeABContactPicked,
                prefillSend = prefillSend,
                observeClearSend = observeClearSend,
                navigateToSelectRecipient = mockk<NavigateToSelectRecipientUseCase>(relaxed = true),
                navigateToSelectSwapRecipient = mockk<NavigateToSelectABSwapRecipientUseCase>(relaxed = true),
                navigateToScanAddress = mockk<NavigateToScanGenericAddressUseCase>(relaxed = true),
                isABContactHintVisibleUseCase =
                    mockk<IsABContactHintVisibleUseCase> { every { observe(any(), any()) } returns flowOf(false) },
                exchangeRateRepository =
                    mockk<ExchangeRateRepository>(relaxed = true) {
                        every { state } returns MutableStateFlow(ExchangeRateState.OptedOut)
                    },
                navigationRouter = navigationRouter,
            )

        fun state(): UnifiedSendState = requireNotNull(vm.state.value)

        /** Lets the tests drive the scheduler without also having to carry a [TestScope] receiver. */
        fun runCurrent() = scope.runCurrent()
    }

    private fun amount(value: String) = NumberTextFieldInnerState.fromAmount(BigDecimal(value))

    private fun assertEqualsBd(expected: String, actual: BigDecimal?) =
        assertEquals(0, BigDecimal(expected).compareTo(assertNotNull(actual)), "expected $expected but was $actual")

    private companion object {
        const val BTC_ADDRESS = "bc1qexampleaddressforunittests"

        fun btc(decimals: Int = 8, usdPrice: BigDecimal = BigDecimal("50000")) =
            swapAsset("BTC", "BTC", usdPrice, decimals)

        fun usdc() = swapAsset("USDC", "ETH", BigDecimal.ONE, decimals = 6)

        /** An asset NEAR listed without a price, so nothing can be valued in USD. */
        fun pricelessToken() = swapAsset("XYZ", "ETH", usdPrice = null, decimals = 8)

        fun zec() = zecAsset()

        fun zecAsset(usdPrice: BigDecimal? = BigDecimal("50")) =
            ZecSwapAsset(
                tokenTicker = "ZEC",
                tokenName = StringResource.ByString("Zcash"),
                tokenIcon = imageRes("ZEC"),
                blockchain = blockchain("ZEC"),
                usdPrice = usdPrice,
                assetId = "ZEC.ZEC",
                decimals = 8,
            )

        fun swapAsset(token: String, chain: String, usdPrice: BigDecimal?, decimals: Int) =
            DynamicSwapAsset(
                tokenTicker = token,
                tokenName = StringResource.ByString(token),
                tokenIcon = imageRes(token),
                usdPrice = usdPrice,
                assetId = "$token.$chain",
                decimals = decimals,
                blockchain = blockchain(chain),
            )

        fun blockchain(chain: String) =
            SwapBlockchain(
                chainTicker = chain,
                chainName = StringResource.ByString(chain),
                chainIcon = imageRes(chain),
            )
    }

    // endregion
}

private class RecordingNavigationRouter : NavigationRouter {
    val forwarded = mutableListOf<Any>()

    override fun forward(vararg routes: Any) {
        forwarded.addAll(routes)
    }

    override fun replace(vararg routes: Any) = Unit

    override fun replaceAll(vararg routes: Any) = Unit

    override fun back() = Unit

    override fun backTo(route: KClass<*>) = Unit

    override fun custom(block: (NavBackStackEntry?) -> NavigationCommand?) = Unit

    override fun backToRoot() = Unit

    override fun observePipeline(): Flow<BaseNavigationCommand> = emptyFlow()
}
