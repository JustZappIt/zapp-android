package co.electriccoin.zcash.ui.common.invest.repository

import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.invest.model.PendingTrade
import co.electriccoin.zcash.ui.common.invest.provider.InvestBuyCheckpointStorageProvider
import co.electriccoin.zcash.ui.common.invest.provider.InvestSellCheckpointStorageProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One trade per stock at a time: a pending sale decides "not sold" from the stock's balance, which a buy landing
 * meanwhile would raise. A trade holds [withLock] from checking the others until its own checkpoint is written.
 */
internal class InvestTradeGuard(
    private val buys: InvestBuyCheckpointStorageProvider,
    private val sells: InvestSellCheckpointStorageProvider,
) {
    private val mutex = Mutex()

    /** Null when the records can't be read; then nothing may be traded. */
    val pendingTrades: Flow<List<PendingTrade>?> =
        combine(buys.observe(), sells.observe()) { buying, selling ->
            buying.map { PendingTrade(it.depositAddress, it.assetId, isSale = false) } +
                selling.map { PendingTrade(it.depositAddress, it.assetId, isSale = true) }
        }.catch<List<PendingTrade>?> { e ->
            if (e is CancellationException) throw e
            Twig.warn(e) { "Invest trade records can't be read" }
            emit(null)
        }

    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }

    suspend fun hasSale(assetId: String): Boolean = sells.observe().first().any { it.assetId == assetId }

    suspend fun hasTrade(assetId: String): Boolean =
        hasSale(assetId) || buys.observe().first().any { it.assetId == assetId }

    suspend fun checkNoTrade(assetId: String) {
        check(!hasTrade(assetId)) { "A buy or sale of this stock is still in progress" }
    }
}
