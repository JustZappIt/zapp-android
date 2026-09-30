// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd.widget

import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapTestnet
import co.electriccoin.zcash.ui.common.privateusd.LocalCurrency
import co.electriccoin.zcash.ui.common.privateusd.ObservePrivateUsdSummaryUseCase
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdAsset
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdBalanceState
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdBalances
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdConversion
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdSummary
import co.electriccoin.zcash.ui.common.privateusd.privateUsdToken
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.privateusd.convert.PrivateUsdConvertArgs
import co.electriccoin.zcash.ui.screen.privateusd.reverse.PrivateUsdReverseArgs
import co.electriccoin.zcash.ui.screen.privateusd.toZec
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Test
import xyz.justzappit.offramp.atomicswap.ReversePhase
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PrivateUsdWidgetVMTest {
    private val summary = MutableStateFlow<PrivateUsdSummary?>(null)
    private val navigation = mockk<NavigationRouter>(relaxed = true)
    private lateinit var vm: PrivateUsdWidgetVM

    @After
    fun tearDown() {
        vm.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun `nothing shows where private USD isn't available`() =
        runTest {
            start()

            assertNull(vm.state.value)
        }

    @Test
    fun `the home line shows the headline, left for the card to hide, and whether screening refused any`() =
        runTest {
            val token = AtomicSwapTestnet.deployment.privateUsdToken
            val balances =
                PrivateUsdBalances(listOf(PrivateUsdAsset(token, available = ONE, processing = ONE, blocked = ONE)))
            summary.value = PrivateUsdSummary(PrivateUsdBalanceState(balances), null, LocalCurrency.DOLLAR)
            start()

            val state = checkNotNull(vm.state.value)
            assertEquals(LocalCurrency.DOLLAR.format(BigDecimal("2.000000")), state.balance)
            assertTrue(state.isBlocked)
            state.onConvertClick()
            verify { navigation.forward(PrivateUsdConvertArgs) }
        }

    @Test
    fun `a conversion back to ZEC under way is where converting goes`() =
        runTest {
            val conversion = PrivateUsdConversion.ToZec(toZec(index = 1, ReversePhase.SETTLING), problem = null)
            summary.value = PrivateUsdSummary(PrivateUsdBalanceState(), conversion, LocalCurrency.DOLLAR)
            start()

            val state = checkNotNull(vm.state.value)
            val banner = checkNotNull(state.conversion)
            state.onConvertClick()
            banner.onClick()

            assertEquals(stringRes(R.string.private_usd_converting_zec), banner.title)
            verify(exactly = 2) { navigation.forward(PrivateUsdReverseArgs) }
        }

    private fun TestScope.start() {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        vm =
            PrivateUsdWidgetVM(
                observePrivateUsdSummary =
                    mockk<ObservePrivateUsdSummaryUseCase> { every { this@mockk.invoke() } returns summary },
                navigationRouter = navigation,
            )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
    }

    private companion object {
        val ONE: BigInteger = BigInteger.valueOf(1_000_000)
    }
}
