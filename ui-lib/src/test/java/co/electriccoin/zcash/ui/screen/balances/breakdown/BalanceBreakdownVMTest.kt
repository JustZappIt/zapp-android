// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.balances.breakdown

import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapTestnet
import co.electriccoin.zcash.ui.common.privateusd.LocalCurrency
import co.electriccoin.zcash.ui.common.privateusd.ObservePrivateUsdSummaryUseCase
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdAsset
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdBalanceState
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdBalances
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdSummary
import co.electriccoin.zcash.ui.common.privateusd.privateUsdToken
import co.electriccoin.zcash.ui.common.repository.ExchangeRateRepository
import co.electriccoin.zcash.ui.common.usecase.BalancePools
import co.electriccoin.zcash.ui.common.usecase.GetBalancePoolsUseCase
import co.electriccoin.zcash.ui.common.wallet.ExchangeRateState
import co.electriccoin.zcash.ui.design.util.asPrivacySensitive
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.privateusd.PrivateUsdArgs
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Test
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class BalanceBreakdownVMTest {
    private val navigation = mockk<NavigationRouter>(relaxed = true)
    private lateinit var vm: BalanceBreakdownVM

    @After
    fun tearDown() {
        vm.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun `the pools sheet's private USD card shows the same summary as the home line`() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            val token = AtomicSwapTestnet.deployment.privateUsdToken
            val balances =
                PrivateUsdBalances(listOf(PrivateUsdAsset(token, available = ONE, processing = ONE, blocked = ONE)))
            val summary = PrivateUsdSummary(PrivateUsdBalanceState(balances), null, LocalCurrency.DOLLAR)
            val pools = BalancePools(Zatoshi(1), Zatoshi(1), Zatoshi(0), Zatoshi(0), Zatoshi(0))
            vm =
                BalanceBreakdownVM(
                    getBalancePools = mockk<GetBalancePoolsUseCase> { every { observe() } returns flowOf(pools) },
                    exchangeRateRepository =
                        mockk<ExchangeRateRepository> {
                            every { state } returns MutableStateFlow(ExchangeRateState.OptedOut)
                        },
                    observePrivateUsdSummary =
                        mockk<ObservePrivateUsdSummaryUseCase> {
                            every { this@mockk.invoke() } returns flowOf(summary)
                        },
                    navigationRouter = navigation,
                )
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }

            val card = checkNotNull(vm.state.value?.privateUsd)
            card.onClick()

            assertEquals(LocalCurrency.DOLLAR.format(BigDecimal("2.000000")).asPrivacySensitive(), card.amount)
            assertEquals(
                stringRes(
                    R.string.private_usd_home_blocked,
                    LocalCurrency.DOLLAR.format(BigDecimal.ONE).asPrivacySensitive(),
                ),
                card.detail,
            )
            assertTrue(card.isBlocked)
            verify { navigation.replace(PrivateUsdArgs) }
        }

    private companion object {
        val ONE: BigInteger = BigInteger.valueOf(1_000_000)
    }
}
