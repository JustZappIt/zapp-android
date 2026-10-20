package co.electriccoin.zcash.ui.common.invest

import co.electriccoin.zcash.ui.common.invest.model.SellProgress
import co.electriccoin.zcash.ui.common.invest.provider.InvestTradeCheckpoint
import co.electriccoin.zcash.ui.common.model.near.SwapStatus
import io.mockk.coVerify
import io.mockk.every
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

@Suppress("MaxLineLength")
internal class InvestSellProgressTest : InvestSellRepositoryTestBase() {
    @Test
    fun `progress runs to sent and refreshes holdings`() =
        runTest {
            checkpoints.add(pastDeadline())
            api.statuses.addAll(listOf(status(SwapStatus.PENDING_DEPOSIT), status(SwapStatus.PROCESSING), status(SwapStatus.SUCCESS)))

            val progress = repository.observeSell(DEPOSIT).toList()

            assertEquals(
                listOf(SellProgress.Authorised(DEPOSIT), SellProgress.Selling(DEPOSIT), SellProgress.Sent(DEPOSIT, BigDecimal("0.0634"))),
                progress,
            )
            coVerify(exactly = 1) { investRepository.refreshHoldings() }
        }

    @Test
    fun `only a sale this phone recorded, and not one needing attention, refreshes holdings`() =
        runTest {
            // Not recorded here (an old sale's screen): its outcome doesn't change what this phone shows.
            api.statuses += status(SwapStatus.SUCCESS)
            repository.observeSell(DEPOSIT).toList()

            // Needs attention: reached again every time following restarts, so no sign-in for it.
            checkpoints.add(InvestTradeCheckpoint(DEPOSIT, nvda.assetId, 0))
            api.statuses += status(SwapStatus.FAILED)
            repository.observeSell(DEPOSIT).toList()

            coVerify(exactly = 0) { investRepository.refreshHoldings() }
        }

    @Test
    fun `an intent past its deadline is unsold only while the stock is still held`() =
        runTest {
            checkpoints.add(pastDeadline())
            api.statuses += status(SwapStatus.PENDING_DEPOSIT)
            now += 14.minutes

            assertEquals(listOf(SellProgress.NotSold(DEPOSIT)), repository.observeSell(DEPOSIT).toList())
            assertTrue(checkpoints.items.value.isEmpty())

            // 1Click never saw it, but the stock left the account: that's for support, not "not sold".
            checkpoints.add(pastDeadline(now - 14.minutes).copy(intentHash = "hash"))
            api.statuses += status(SwapStatus.PENDING_DEPOSIT)
            holdings.value = "0"

            assertEquals(listOf(SellProgress.NeedsAttention(DEPOSIT, "hash")), repository.observeSell(DEPOSIT).toList())
            assertEquals(1, checkpoints.items.value.size)
        }

    @Test
    fun `a sale another observer just settled as not sold is judged the same way`() =
        runTest {
            checkpoints.add(pastDeadline())
            api.statuses += status(SwapStatus.PENDING_DEPOSIT)
            now += 14.minutes
            // The record is gone by the time this observer's poll returns: the progress screen and the shared
            // follower both poll, and the other one settled the sale first.
            api.onStatus = { checkpoints.items.value = emptyList() }

            assertEquals(listOf(SellProgress.NotSold(DEPOSIT)), repository.observeSell(DEPOSIT).toList())
        }

    @Test
    fun `a sale with no record stops waiting once its intent can't run any more`() =
        runTest {
            // No record (say the screen came back after the app was killed, once the sale had settled): the intent's
            // deadline is unknown, but at most an hour past the quote's 14:30.
            val waiting = status(SwapStatus.PENDING_DEPOSIT)
            api.statuses += waiting
            now += 95.minutes
            assertEquals(SellProgress.Authorised(DEPOSIT), repository.observeSell(DEPOSIT).first())

            api.statuses += waiting
            now += 1.minutes
            assertEquals(listOf(SellProgress.NeedsAttention(DEPOSIT, DEPOSIT)), repository.observeSell(DEPOSIT).toList())
        }

    @Test
    fun `status not knowing the sale yet is waiting until the intent's deadline`() =
        runTest {
            checkpoints.add(pastDeadline())
            api.statuses.addAll(listOf(notFound(), notFound(), notFound(), status(SwapStatus.SUCCESS)))

            assertEquals(
                List(3) { SellProgress.Authorised(DEPOSIT) } + SellProgress.Sent(DEPOSIT, BigDecimal("0.0634")),
                repository.observeSell(DEPOSIT).toList(),
            )

            // Past the deadline an unknown sale is settled by the balance, like one still pending.
            checkpoints.add(pastDeadline())
            now += 14.minutes
            api.statuses.addAll(listOf(notFound()))
            assertEquals(listOf(SellProgress.NotSold(DEPOSIT)), repository.observeSell(DEPOSIT).toList())

            // With no checkpoint there's no deadline to wait for: repeated rejections go to support.
            api.statuses.addAll(listOf(notFound(), notFound(), notFound()))
            assertEquals(listOf(SellProgress.NeedsAttention(DEPOSIT, DEPOSIT)), repository.observeSell(DEPOSIT).toList())
        }

    @Test
    fun `a refund returns the stock and a failure stays listed`() =
        runTest {
            api.statuses += status(SwapStatus.REFUNDED)
            assertEquals(listOf(SellProgress.ReturnedToAccount(DEPOSIT)), repository.observeSell(DEPOSIT).toList())

            checkpoints.add(InvestTradeCheckpoint(DEPOSIT, nvda.assetId, 0))
            api.statuses += status(SwapStatus.FAILED)
            assertEquals(listOf(SellProgress.NeedsAttention(DEPOSIT, DEPOSIT)), repository.observeSell(DEPOSIT).toList())
            assertEquals(1, checkpoints.items.value.size)
            repository.dismissSell(DEPOSIT)
            assertNull(checkpoints.items.value.firstOrNull())
        }
}
