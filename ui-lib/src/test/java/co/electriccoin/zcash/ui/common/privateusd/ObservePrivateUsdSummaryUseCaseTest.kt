// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.privateusd

import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapProblem
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapRepository
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapState
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapTestnet
import co.electriccoin.zcash.ui.common.atomicswap.ReverseSwapRepository
import co.electriccoin.zcash.ui.common.atomicswap.ReverseSwapState
import co.electriccoin.zcash.ui.screen.privateusd.toUsd
import co.electriccoin.zcash.ui.screen.privateusd.toZec
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import xyz.justzappit.offramp.atomicswap.AtomicSwapOutcome
import xyz.justzappit.offramp.atomicswap.ReversePhase
import xyz.justzappit.railgun.RailgunNetwork
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ObservePrivateUsdSummaryUseCaseTest {
    private val token = PrivateUsdTokens.of(RailgunNetwork.SEPOLIA).first { it.isDollar }
    private val available = MutableStateFlow(true)
    private val balance = MutableStateFlow(PrivateUsdBalanceState())
    private val forward = MutableStateFlow(AtomicSwapState())
    private val reverse = MutableStateFlow(ReverseSwapState())
    private var isBalanceBuilt = false
    private var isConversionBuilt = false

    private val useCase =
        ObservePrivateUsdSummaryUseCase(
            observePrivateUsdAvailable =
                mockk<ObservePrivateUsdAvailableUseCase> { every { this@mockk.invoke() } returns available },
            observeLocalCurrency =
                mockk<ObserveLocalCurrencyUseCase> {
                    every { this@mockk.invoke() } returns flowOf(LocalCurrency.DOLLAR)
                },
            balanceRepository =
                lazy {
                    isBalanceBuilt = true
                    mockk<PrivateUsdBalanceRepository> { every { observeIfUsed() } returns balance }
                },
            observeConversion =
                lazy {
                    isConversionBuilt = true
                    ObservePrivateUsdConversionUseCase(
                        atomicSwapRepository =
                            mockk<AtomicSwapRepository> {
                                every { state } returns forward
                                every { deployment } returns AtomicSwapTestnet.deployment
                            },
                        reverseSwapRepository = mockk<ReverseSwapRepository> { every { state } returns reverse },
                    )
                },
        )

    @Test
    fun `where private USD isn't available there's no summary, and nothing behind it is built`() =
        runTest {
            available.value = false

            assertNull(useCase().first())
            assertFalse(isBalanceBuilt)
            assertFalse(isConversionBuilt)
        }

    @Test
    fun `the summary says when screening refused some of the balance`() =
        runTest {
            balance.value = balance(PrivateUsdAsset(token, available = ONE_DOLLAR, blocked = ONE_DOLLAR))

            val summary = checkNotNull(useCase().first())

            assertTrue(summary.isBlocked)
            assertEquals(balance.value, summary.balance)
            assertEquals(LocalCurrency.DOLLAR, summary.currency)
        }

    @Test
    fun `a conversion under way shows whichever way it runs`() =
        runTest {
            assertNull(checkNotNull(useCase().first()).conversion)

            val intoUsd = AtomicSwapState(record = toUsd(index = 0, at = 10, outcome = null))
            forward.value = intoUsd
            assertEquals(
                PrivateUsdConversion.ToUsd(intoUsd, AtomicSwapTestnet.deployment.makerConfirmations),
                checkNotNull(useCase().first()).conversion,
            )

            forward.value = AtomicSwapState(record = toUsd(index = 0, at = 10, AtomicSwapOutcome.Paid))
            val outOfUsd = toZec(index = 1, ReversePhase.RECEIVING_ZEC)
            reverse.value = ReverseSwapState(outOfUsd, problem = AtomicSwapProblem.UNEXPECTED)
            assertEquals(
                PrivateUsdConversion.ToZec(outOfUsd, problem = AtomicSwapProblem.UNEXPECTED),
                checkNotNull(useCase().first()).conversion,
            )
        }

    private fun balance(vararg assets: PrivateUsdAsset) =
        PrivateUsdBalanceState(balances = PrivateUsdBalances(assets.toList()))

    private companion object {
        val ONE_DOLLAR: BigInteger = BigInteger.valueOf(1_000_000)
    }
}
