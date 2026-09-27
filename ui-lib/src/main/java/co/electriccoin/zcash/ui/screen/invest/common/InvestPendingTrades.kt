package co.electriccoin.zcash.ui.screen.invest.common

import co.electriccoin.zcash.ui.common.invest.provider.InvestBuyCheckpointStorageProvider
import co.electriccoin.zcash.ui.common.invest.provider.InvestSellCheckpointStorageProvider
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.screen.invest.progress.InvestProgressArgs
import co.electriccoin.zcash.ui.screen.invest.sellprogress.InvestSellProgressArgs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/** A buy or sale that has been sent and isn't final yet, with the stock it is for. */
data class PendingTrade(
    val depositAddress: String,
    val assetId: String,
    val isSale: Boolean,
)

/** Where a pending trade is followed: I7 for a buy, I10 for a sale (each with support and Dismiss if stuck). */
internal fun PendingTrade.progressRoute(): Any =
    if (isSale) {
        InvestSellProgressArgs(depositAddress = depositAddress, assetId = assetId)
    } else {
        InvestProgressArgs(depositAddress = depositAddress, assetId = assetId)
    }

/**
 * "A trade of NVIDIA is in progress", shown where Buy or Sell is off because of it, with the way to that trade.
 */
internal data class InvestTradeInProgressState(
    val text: StringResource,
    val onOpen: () -> Unit,
)

/**
 * The trades still in progress, including stuck ones until they are dismissed. While one is pending for a stock the
 * engine refuses to buy or sell that stock, so the screens say so, link to it, and don't offer the trade rather
 * than let the call fail.
 *
 * `pendingBuys` and `pendingSells` carry only deposit addresses, so this reads the engine's checkpoints (read only)
 * until the repositories expose the stock too.
 */
fun interface InvestPendingTrades {
    fun observe(): Flow<List<PendingTrade>>
}

class InvestPendingTradesImpl(
    private val buys: InvestBuyCheckpointStorageProvider,
    private val sales: InvestSellCheckpointStorageProvider,
) : InvestPendingTrades {
    override fun observe(): Flow<List<PendingTrade>> =
        combine(buys.observe(), sales.observe()) { pendingBuys, pendingSales ->
            pendingBuys.map { PendingTrade(it.depositAddress, it.assetId, isSale = false) } +
                pendingSales.map { PendingTrade(it.depositAddress, it.assetId, isSale = true) }
        }.distinctUntilChanged()
}
