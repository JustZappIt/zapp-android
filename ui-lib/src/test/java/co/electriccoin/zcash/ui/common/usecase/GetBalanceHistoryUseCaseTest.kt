package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.model.TransactionId
import cash.z.ecc.android.sdk.model.TransactionOverview
import cash.z.ecc.android.sdk.model.TransactionState
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.Zip318Kind
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.repository.ReceiveTransaction
import co.electriccoin.zcash.ui.common.repository.SendTransaction
import co.electriccoin.zcash.ui.common.repository.ShieldTransaction
import co.electriccoin.zcash.ui.common.repository.Transaction
import co.electriccoin.zcash.ui.common.repository.TransactionRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.time.Instant

class GetBalanceHistoryUseCaseTest {
    @Test
    fun confirmed_balance_excludes_pending_shielded_value() =
        runTest {
            val account =
                mockk<WalletAccount> {
                    every { totalBalance } returns Zatoshi(100)
                    every { pendingShieldedBalance } returns Zatoshi(100)
                }
            val transactionRepository =
                mockk<TransactionRepository> {
                    every { balanceHistoryTransactions } returns flowOf(emptyList())
                }
            val accountDataSource =
                mockk<AccountDataSource> {
                    every { selectedAccount } returns flowOf(account)
                }

            val result = GetBalanceHistoryUseCase(transactionRepository, accountDataSource).observe().first()

            assertEquals(BalanceHistory.Reconciled(emptyList(), Zatoshi(0)), result)
        }

    @Test
    fun observes_lightweight_sdk_rows_without_waiting_for_enriched_transactions() =
        runTest {
            val account =
                mockk<WalletAccount> {
                    every { totalBalance } returns Zatoshi(100)
                    every { pendingShieldedBalance } returns Zatoshi(0)
                }
            val overview = transaction(Kind.RECEIVE, 100, DAY_1).overview
            val transactionRepository =
                mockk<TransactionRepository> {
                    every { balanceHistoryTransactions } returns flowOf(listOf(overview))
                }
            val accountDataSource =
                mockk<AccountDataSource> {
                    every { selectedAccount } returns flowOf(account)
                }

            val result = GetBalanceHistoryUseCase(transactionRepository, accountDataSource).observe().first()

            assertEquals(listOf(100L), result!!.balances())
        }

    @Test
    fun receive_uses_positive_overview_net_value() {
        val result = buildBalanceHistory(listOf(transaction(Kind.RECEIVE, 100, DAY_1)), Zatoshi(100))

        assertEquals(listOf(100L), result.balances())
    }

    @Test
    fun ordinary_send_includes_amount_and_fee_from_net_value() {
        val result =
            buildBalanceHistory(
                listOf(
                    transaction(Kind.RECEIVE, 100, DAY_1),
                    transaction(Kind.SEND, 31, DAY_2),
                ),
                Zatoshi(69),
            )

        assertEquals(listOf(100L, 69L), result.balances())
    }

    @Test
    fun shielding_and_pool_migration_fees_reduce_balance() {
        val result =
            buildBalanceHistory(
                listOf(
                    transaction(Kind.RECEIVE, 100, DAY_1),
                    transaction(Kind.SHIELD, 1, DAY_2),
                    transaction(Kind.MIGRATION, 2, DAY_3),
                ),
                Zatoshi(97),
            )

        assertEquals(listOf(100L, 99L, 97L), result.balances())
    }

    @Test
    fun pending_and_failed_transactions_are_excluded() {
        val result =
            buildBalanceHistory(
                listOf(
                    transaction(Kind.RECEIVE, 100, DAY_1),
                    transaction(Kind.PENDING_SEND, 20, DAY_2),
                    transaction(Kind.FAILED_RECEIVE, 50, DAY_3),
                ),
                Zatoshi(100),
            )

        assertEquals(listOf(100L), result.balances())
    }

    @Test
    fun same_timestamp_transactions_are_accumulated_into_one_stable_point() {
        val result =
            buildBalanceHistory(
                listOf(
                    transaction(Kind.SEND, 30, DAY_1),
                    transaction(Kind.RECEIVE, 100, DAY_1),
                ),
                Zatoshi(70),
            )

        assertEquals(listOf(70L), result.balances())
    }

    @Test
    fun points_are_sorted_chronologically() {
        val result =
            buildBalanceHistory(
                listOf(
                    transaction(Kind.RECEIVE, 5, DAY_3),
                    transaction(Kind.RECEIVE, 10, DAY_1),
                    transaction(Kind.RECEIVE, 20, DAY_2),
                ),
                Zatoshi(35),
            ) as BalanceHistory.Reconciled

        assertEquals(listOf(DAY_1, DAY_2, DAY_3), result.points.map(BalanceHistoryPoint::timestamp))
        assertEquals(listOf(10L, 30L, 35L), result.balances())
    }

    @Test
    fun accumulation_overflow_fails_closed() {
        val result =
            buildBalanceHistory(
                List(4_393) { transaction(Kind.RECEIVE, 2_100_000_000_000_000L, DAY_1) },
                Zatoshi(0),
            )

        assertSame(BalanceHistory.Inconsistent, result)
    }

    @Test
    fun final_reconstructed_balance_must_reconcile() {
        val result = buildBalanceHistory(listOf(transaction(Kind.RECEIVE, 100, DAY_1)), Zatoshi(99))

        assertSame(BalanceHistory.Inconsistent, result)
    }

    @Test
    fun settled_non_zero_transaction_without_block_time_fails_closed() {
        val result = buildBalanceHistory(listOf(transaction(Kind.RECEIVE, 100, null)), Zatoshi(100))

        assertSame(BalanceHistory.Inconsistent, result)
    }

    private fun transaction(
        kind: Kind,
        netValue: Long,
        timestamp: Instant?,
    ): Transaction {
        val sent = kind in setOf(Kind.SEND, Kind.SHIELD, Kind.MIGRATION, Kind.PENDING_SEND)
        val state =
            when (kind) {
                Kind.PENDING_SEND -> TransactionState.Pending
                Kind.FAILED_RECEIVE -> TransactionState.Expired
                else -> TransactionState.Confirmed
            }
        val overview =
            TransactionOverview(
                txId = TransactionId.new(ByteArray(32)),
                minedHeight = null,
                expiryHeight = null,
                index = null,
                raw = null,
                isSentTransaction = sent,
                netValue = Zatoshi(netValue),
                totalSpent = Zatoshi(netValue),
                totalReceived = Zatoshi(0),
                feePaid = null,
                isChange = false,
                receivedNoteCount = 0,
                sentNoteCount = 0,
                memoCount = 0,
                blockTimeEpochSeconds = timestamp?.epochSecond,
                transactionState = state,
                isShielding = kind == Kind.SHIELD,
                spentNoteCount = 0,
                poolCrossingValue = null,
                isTrusted = false,
                zip318Kind = if (kind == Kind.MIGRATION) Zip318Kind.TRANSFER else Zip318Kind.NOT_CLASSIFIED,
            )
        return when (kind) {
            Kind.RECEIVE -> receiveSuccess(timestamp, overview)
            Kind.SEND, Kind.MIGRATION -> sendSuccess(timestamp, overview)
            Kind.SHIELD -> shieldSuccess(timestamp, overview)
            Kind.PENDING_SEND -> sendPending(timestamp, overview)
            Kind.FAILED_RECEIVE -> receiveFailed(timestamp, overview)
        }
    }

    private enum class Kind { RECEIVE, SEND, SHIELD, MIGRATION, PENDING_SEND, FAILED_RECEIVE }

    private companion object {
        val DAY_1: Instant = Instant.parse("2026-08-01T00:00:00Z")
        val DAY_2: Instant = Instant.parse("2026-08-02T00:00:00Z")
        val DAY_3: Instant = Instant.parse("2026-08-03T00:00:00Z")
    }
}

private fun BalanceHistory.balances(): List<Long> =
    (this as BalanceHistory.Reconciled).points.map { it.balance.value }

private fun receiveSuccess(
    timestamp: Instant?,
    overview: TransactionOverview,
): ReceiveTransaction.Success =
    ReceiveTransaction.Success(
        id = overview.txId,
        amount = overview.netValue,
        timestamp = timestamp,
        memoCount = 0,
        transactionOutputs = emptyList(),
        overview = overview,
        recipient = null,
    )

private fun receiveFailed(
    timestamp: Instant?,
    overview: TransactionOverview,
): ReceiveTransaction.Failed =
    ReceiveTransaction.Failed(
        id = overview.txId,
        amount = overview.netValue,
        timestamp = timestamp,
        memoCount = 0,
        transactionOutputs = emptyList(),
        overview = overview,
        recipient = null,
    )

private fun sendSuccess(
    timestamp: Instant?,
    overview: TransactionOverview,
): SendTransaction.Success =
    SendTransaction.Success(
        id = overview.txId,
        amount = overview.netValue,
        timestamp = timestamp,
        memoCount = 0,
        fee = null,
        transactionOutputs = emptyList(),
        overview = overview,
        recipient = null,
    )

private fun sendPending(
    timestamp: Instant?,
    overview: TransactionOverview,
): SendTransaction.Pending =
    SendTransaction.Pending(
        id = overview.txId,
        amount = overview.netValue,
        timestamp = timestamp,
        memoCount = 0,
        fee = null,
        transactionOutputs = emptyList(),
        overview = overview,
        recipient = null,
    )

private fun shieldSuccess(
    timestamp: Instant?,
    overview: TransactionOverview,
): ShieldTransaction.Success =
    ShieldTransaction.Success(
        id = overview.txId,
        amount = overview.netValue,
        timestamp = timestamp,
        memoCount = 0,
        fee = overview.netValue,
        transactionOutputs = emptyList(),
        overview = overview,
        recipient = null,
    )
