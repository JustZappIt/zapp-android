package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.repository.ReceiveTransaction
import co.electriccoin.zcash.ui.common.repository.SendTransaction
import co.electriccoin.zcash.ui.common.repository.Transaction
import co.electriccoin.zcash.ui.common.repository.TransactionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.time.Instant

class GetBalanceHistoryUseCase(
    private val transactionRepository: TransactionRepository,
) {
    fun observe(): Flow<List<BalanceHistoryPoint>?> =
        transactionRepository.transactions
            .map { transactions -> transactions?.let(::buildHistory) }
            .flowOn(Dispatchers.Default)

    private fun buildHistory(transactions: List<Transaction>): List<BalanceHistoryPoint> {
        val ordered =
            transactions
                .asSequence()
                .mapNotNull { tx -> tx.timestamp?.let { it to tx } }
                .sortedBy { it.first }
                .toList()

        if (ordered.isEmpty()) return emptyList()

        val points = ArrayList<BalanceHistoryPoint>(ordered.size)
        var running = 0L
        for ((timestamp, tx) in ordered) {
            running += tx.signedDeltaZatoshi()
            points += BalanceHistoryPoint(timestamp = timestamp, balance = Zatoshi(running.coerceAtLeast(0)))
        }
        return points
    }
}

data class BalanceHistoryPoint(
    val timestamp: Instant,
    val balance: Zatoshi,
)

private fun Transaction.signedDeltaZatoshi(): Long =
    when (this) {
        // Only settled (Success) sends/receives move the confirmed balance the chart tracks.
        // Pending/Failed never settled; shields move funds within the wallet (no net change).
        is ReceiveTransaction.Success -> amount.value

        is SendTransaction.Success -> -amount.value

        else -> 0L
    }
