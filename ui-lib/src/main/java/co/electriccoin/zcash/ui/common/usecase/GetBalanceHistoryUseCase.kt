package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.model.TransactionOverview
import cash.z.ecc.android.sdk.model.TransactionState
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.repository.ReceiveTransaction
import co.electriccoin.zcash.ui.common.repository.SendTransaction
import co.electriccoin.zcash.ui.common.repository.ShieldTransaction
import co.electriccoin.zcash.ui.common.repository.Transaction
import co.electriccoin.zcash.ui.common.repository.TransactionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import java.lang.Math.addExact
import java.lang.Math.negateExact
import java.lang.Math.subtractExact
import java.time.Instant

class GetBalanceHistoryUseCase(
    private val transactionRepository: TransactionRepository,
    private val accountDataSource: AccountDataSource,
) {
    fun observe(): Flow<BalanceHistory?> =
        combine(
            transactionRepository.balanceHistoryTransactions,
            accountDataSource.selectedAccount,
        ) { transactions, account ->
            if (transactions == null || account == null) {
                null
            } else {
                val confirmedBalance =
                    runCatching {
                        Zatoshi(subtractExact(account.totalBalance.value, account.pendingShieldedBalance.value))
                    }.getOrNull() ?: return@combine BalanceHistory.Inconsistent
                buildBalanceHistoryFromOverviews(transactions, confirmedBalance)
            }
        }.flowOn(Dispatchers.Default)
}

sealed interface BalanceHistory {
    data class Reconciled(
        val points: List<BalanceHistoryPoint>,
        val confirmedBalance: Zatoshi,
    ) : BalanceHistory

    data object Inconsistent : BalanceHistory
}

data class BalanceHistoryPoint(
    val timestamp: Instant,
    val balance: Zatoshi,
)

internal fun buildBalanceHistory(
    transactions: List<Transaction>,
    confirmedBalance: Zatoshi,
): BalanceHistory =
    try {
        reconcileBalanceHistory(
            transactions.map { transaction ->
                HistoricalTransaction(
                    timestamp = transaction.timestamp,
                    isSettled = transaction.isSettled,
                    signedNetValue = transaction.signedNetValue(),
                )
            },
            confirmedBalance,
        )
    } catch (_: ArithmeticException) {
        BalanceHistory.Inconsistent
    }

private fun buildBalanceHistoryFromOverviews(
    transactions: List<TransactionOverview>,
    confirmedBalance: Zatoshi,
): BalanceHistory =
    try {
        reconcileBalanceHistory(
            transactions.map { transaction ->
                HistoricalTransaction(
                    timestamp = transaction.blockTimeEpochSeconds?.let(Instant::ofEpochSecond),
                    isSettled = transaction.transactionState == TransactionState.Confirmed,
                    signedNetValue =
                        if (transaction.isSentTransaction) {
                            negateExact(transaction.netValue.value)
                        } else {
                            transaction.netValue.value
                        },
                )
            },
            confirmedBalance,
        )
    } catch (_: ArithmeticException) {
        BalanceHistory.Inconsistent
    }

@Suppress("NestedBlockDepth", "ReturnCount")
private fun reconcileBalanceHistory(
    transactions: List<HistoricalTransaction>,
    confirmedBalance: Zatoshi,
): BalanceHistory {
    val deltasByTimestamp = linkedMapOf<Instant, Long>()

    try {
        transactions
            .asSequence()
            .filter(HistoricalTransaction::isSettled)
            .forEach { transaction ->
                val delta = transaction.signedNetValue
                val timestamp = transaction.timestamp
                if (timestamp == null) {
                    // A settled non-zero delta without a block time cannot be placed on a historical
                    // curve. It must not be assigned a synthetic timestamp.
                    if (delta != 0L) return BalanceHistory.Inconsistent
                } else {
                    deltasByTimestamp[timestamp] = addExact(deltasByTimestamp[timestamp] ?: 0L, delta)
                }
            }

        var runningBalance = 0L
        val points = ArrayList<BalanceHistoryPoint>(deltasByTimestamp.size)
        deltasByTimestamp
            .toSortedMap()
            .forEach { (timestamp, delta) ->
                runningBalance = addExact(runningBalance, delta)
                if (runningBalance < 0L) return BalanceHistory.Inconsistent
                points += BalanceHistoryPoint(timestamp, Zatoshi(runningBalance))
            }

        if (runningBalance != confirmedBalance.value) return BalanceHistory.Inconsistent
        return BalanceHistory.Reconciled(points = points, confirmedBalance = confirmedBalance)
    } catch (_: ArithmeticException) {
        return BalanceHistory.Inconsistent
    }
}

private data class HistoricalTransaction(
    val timestamp: Instant?,
    val isSettled: Boolean,
    val signedNetValue: Long,
)

private val Transaction.isSettled: Boolean
    get() =
        this is ReceiveTransaction.Success ||
            this is SendTransaction.Success ||
            this is ShieldTransaction.Success

private fun Transaction.signedNetValue(): Long =
    if (overview.isSentTransaction) negateExact(overview.netValue.value) else overview.netValue.value
