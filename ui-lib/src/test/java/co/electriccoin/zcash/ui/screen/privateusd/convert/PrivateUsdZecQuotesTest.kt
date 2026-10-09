// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd.convert

import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapQuote
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapRepository
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapTestnet
import co.electriccoin.zcash.ui.screen.privateusd.offer
import co.electriccoin.zcash.ui.screen.privateusd.requested
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import xyz.justzappit.offramp.atomicswap.AtomicSwapBlock
import xyz.justzappit.offramp.atomicswap.AtomicSwapBlockedException
import xyz.justzappit.offramp.p2p.Usdc6
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PrivateUsdZecQuotesTest {
    private val deployment = AtomicSwapTestnet.deployment
    private val repository = mockk<AtomicSwapRepository>()
    private var now = NOW
    private val quotes = PrivateUsdZecQuotes(repository, deployment) { now }

    @Test
    fun `the price comes from the deployment's least amount, then each quote prices the next while it holds`() =
        runTest {
            coEvery { repository.quote(any()) } answers { quote(requested()) }

            quotes.quote(50_000_000)
            quotes.quote(40_000_000)
            now = NOW + VALIDITY
            quotes.quote(30_000_000)

            coVerify(exactly = 2) { repository.quote(deployment.minAmount) }
            coVerify(exactly = 5) { repository.quote(any()) }
        }

    @Test
    fun `a quote costing more than the total is asked for again, smaller, with its real fee`() =
        runTest {
            coEvery { repository.quote(any()) } answers {
                val amount = requested()
                quote(amount, feeZat = if (amount == deployment.minAmount) FEE_ZAT else 25_000)
            }

            val quote = quotes.quote(50_000_000)

            assertEquals(49_999_999L, quote.totalZat)
            assertEquals(25_000L, quote.feeZat)
            coVerify(exactly = 3) { repository.quote(any()) }
        }

    @Test
    fun `what a quote costs in all is the most its deposit may cost`() =
        runTest {
            coEvery { repository.quote(any()) } answers { quote(requested()) }

            val quote = quotes.quote(50_000_000)

            assertEquals(quote.totalZat, quote.offer.maxTotalZat)
            assertTrue(quote.totalZat <= 50_000_000)
        }

    @Test
    fun `an offer whose fee the wallet can't work out can't be paid`() =
        runTest {
            coEvery { repository.quote(any()) } answers { quote(requested(), feeZat = null) }

            val failure = assertFailsWith<AtomicSwapBlockedException> { quotes.quote(50_000_000) }

            assertEquals(AtomicSwapBlock.DEPOSIT_UNPAYABLE, failure.reason)
        }

    @Test
    fun `the maximum stops at the deployment's most`() =
        runTest {
            coEvery { repository.quote(any()) } answers { quote(requested()) }

            val quote = quotes.maximum(10 * ONE_ZEC)

            assertEquals(deployment.maxAmount, quote.offer.requested)
        }

    @Test
    fun `an amount outside the deployment's limits has no quote`() =
        runTest {
            coEvery { repository.quote(any()) } answers { quote(requested()) }

            assertFailsWith<ZecInputQuoteException> { quotes.quote(100_000) }
            assertFailsWith<ZecInputQuoteException> { quotes.quote(10 * ONE_ZEC) }
        }

    @Test
    fun `typed ZEC pays for the maker's network cost as well as the amount`() =
        runTest {
            coEvery { repository.quote(any()) } answers { quote(requested(), networkCost = NETWORK_COST) }

            val quote = quotes.quote(3_000_000 + FEE_ZAT)

            assertEquals(Usdc6.ofMicros(780_000), quote.offer.requested)
            assertEquals(3_000_000 + FEE_ZAT, quote.totalZat)
            coVerify(exactly = 2) { repository.quote(any()) }
        }

    @Test
    fun `a quote that ran out is asked for again at its size first`() =
        runTest {
            coEvery { repository.quote(any()) } answers { quote(requested()) }

            quotes.quote(50_000_000, requested = Usdc6.ofMicros(16_000_000))

            coVerify(exactly = 1) { repository.quote(Usdc6.ofMicros(16_000_000)) }
            coVerify(exactly = 1) { repository.quote(any()) }
        }

    private fun quote(
        requested: Usdc6,
        feeZat: Long? = FEE_ZAT,
        networkCost: Long? = null,
    ): AtomicSwapQuote {
        val units = requested.micros.toLong()
        val depositZat = (units + (networkCost ?: 0)) * ZAT_PER_UNIT
        val offer =
            offer(units.toInt(), units, depositZat, expiresAt = now + VALIDITY, networkCost = networkCost)
        return AtomicSwapQuote(offer, feeZat)
    }

    private companion object {
        const val NOW = 1_790_000_000L
        const val VALIDITY = 300L
        const val ONE_ZEC = 100_000_000L
        const val ZAT_PER_UNIT = 3L
        const val FEE_ZAT = 10_000L
        const val NETWORK_COST = 220_000L
    }
}
