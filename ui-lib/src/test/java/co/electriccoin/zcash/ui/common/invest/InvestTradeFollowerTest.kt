package co.electriccoin.zcash.ui.common.invest

import co.electriccoin.zcash.ui.common.invest.model.BuyProgress
import co.electriccoin.zcash.ui.common.invest.model.PendingTrade
import co.electriccoin.zcash.ui.common.invest.model.SellProgress
import co.electriccoin.zcash.ui.common.invest.provider.InvestBuyCheckpoint
import co.electriccoin.zcash.ui.common.invest.provider.InvestBuyCheckpointStorageProvider
import co.electriccoin.zcash.ui.common.invest.provider.InvestSellCheckpointStorageProvider
import co.electriccoin.zcash.ui.common.invest.repository.InvestRepository
import co.electriccoin.zcash.ui.common.invest.repository.InvestSellRepository
import co.electriccoin.zcash.ui.common.invest.repository.InvestTradeFollowerImpl
import co.electriccoin.zcash.ui.common.invest.repository.InvestTradeGuard
import co.electriccoin.zcash.ui.common.provider.StoreCorruptedException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("MaxLineLength")
class InvestTradeFollowerTest {
    private val pending = MutableStateFlow<List<PendingTrade>?>(emptyList())
    private val cancelled = mutableListOf<String>()
    private val buys =
        mockk<InvestRepository> {
            every { pendingTrades } returns pending
            every { observeBuy(any()) } answers {
                val address = firstArg<String>()
                flow<BuyProgress> {
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled += address
                    }
                }
            }
        }
    private val sells =
        mockk<InvestSellRepository> {
            every { observeSell(any()) } answers { flowOf(SellProgress.Sent(firstArg(), null)) }
        }

    @Test
    fun `every pending trade is followed once, however many screens ask`() =
        runTest {
            val follower = InvestTradeFollowerImpl(buys, sells, backgroundScope)
            pending.value = listOf(PendingTrade("buy-1", "nvda", isSale = false), PendingTrade("sell-1", "tsla", isSale = true))

            backgroundScope.launch { follower.followPendingTrades() }
            backgroundScope.launch { follower.followPendingTrades() }
            runCurrent()

            verify(exactly = 1) { buys.observeBuy("buy-1") }
            verify(exactly = 1) { sells.observeSell("sell-1") }
        }

    @Test
    fun `a trade that leaves the list stops being polled, and none is polled once nobody asks`() =
        runTest {
            val follower = InvestTradeFollowerImpl(buys, sells, backgroundScope)
            pending.value = listOf(PendingTrade("buy-1", "nvda", isSale = false), PendingTrade("buy-2", "aapl", isSale = false))
            val screen = backgroundScope.launch { follower.followPendingTrades() }
            runCurrent()

            pending.value = listOf(PendingTrade("buy-2", "aapl", isSale = false))
            runCurrent()
            assertEquals(listOf("buy-1"), cancelled)

            screen.cancel()
            runCurrent()
            assertEquals(listOf("buy-1", "buy-2"), cancelled)
        }

    @Test
    fun `unreadable trade records are reported as unknown, not as none`() =
        runTest {
            val guard =
                InvestTradeGuard(
                    buys = mockk<InvestBuyCheckpointStorageProvider> { every { observe() } returns flowOf(emptyList()) },
                    sells =
                        mockk<InvestSellCheckpointStorageProvider> {
                            every { observe() } returns
                                flow<List<InvestBuyCheckpoint>> { throw StoreCorruptedException("k", IllegalStateException()) }
                        },
                )

            assertNull(guard.pendingTrades.first())
            assertTrue(cancelled.isEmpty())
        }
}
