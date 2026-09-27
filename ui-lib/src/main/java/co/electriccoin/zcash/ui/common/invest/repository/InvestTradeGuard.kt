package co.electriccoin.zcash.ui.common.invest.repository

import co.electriccoin.zcash.ui.common.invest.model.PendingTrade
import co.electriccoin.zcash.ui.common.invest.provider.InvestBuyCheckpointStorageProvider
import co.electriccoin.zcash.ui.common.invest.provider.InvestSellCheckpointStorageProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps a stock's buys and sales apart. A pending sale decides "not sold" from the stock's balance, which a
 * buy landing meanwhile would raise, so neither may start while the other is pending. A buy or sale holds
 * [withLock] from checking the other side until its own checkpoint is written, so the two can't interleave.
 */
internal class InvestTradeGuard(
    private val buys: InvestBuyCheckpointStorageProvider,
    private val sells: InvestSellCheckpointStorageProvider,
) {
    private val mutex = Mutex()

    val pendingTrades: Flow<List<PendingTrade>> =
        combine(buys.observe(), sells.observe()) { buying, selling ->
            buying.map { PendingTrade(it.depositAddress, it.assetId, isSale = false) } +
                selling.map { PendingTrade(it.depositAddress, it.assetId, isSale = true) }
        }

    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }

    /** A sale of [assetId] not yet final, including one that needs attention until it is dismissed. */
    suspend fun hasSale(assetId: String): Boolean = sells.observe().first().any { it.assetId == assetId }

    /** Any buy or sale of [assetId] not yet final. */
    suspend fun hasTrade(assetId: String): Boolean =
        hasSale(assetId) || buys.observe().first().any { it.assetId == assetId }
}
