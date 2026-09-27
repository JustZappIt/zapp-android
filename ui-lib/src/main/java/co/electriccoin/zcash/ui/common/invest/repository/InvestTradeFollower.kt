package co.electriccoin.zcash.ui.common.invest.repository

import co.electriccoin.zcash.ui.common.bestEffort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch

/**
 * Follows every pending buy and sale to its final state, so a trade settles (and its stock unlocks) without
 * its progress screen being open. Call [followPendingTrades] from whatever shows Invest positions, for as
 * long as it is shown; any number of callers share one follower, which stops when the last one leaves.
 */
interface InvestTradeFollower {
    suspend fun followPendingTrades(): Nothing
}

internal class InvestTradeFollowerImpl(
    private val buys: InvestRepository,
    private val sells: InvestSellRepository,
    scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
) : InvestTradeFollower {
    private val following =
        flow<Unit> { follow() }.shareIn(scope, SharingStarted.WhileSubscribed())

    override suspend fun followPendingTrades(): Nothing = following.collect {}

    /** One poller per pending trade; a trade that leaves the list (final, or dismissed) stops its poller. */
    private suspend fun follow() =
        coroutineScope {
            val pollers = mutableMapOf<String, Job>()
            buys.pendingTrades.collect { trades ->
                val pending = trades.orEmpty().associateBy { it.depositAddress }
                (pollers.keys - pending.keys).forEach { pollers.remove(it)?.cancel() }
                pending.values.filter { it.depositAddress !in pollers }.forEach { trade ->
                    pollers[trade.depositAddress] =
                        launch {
                            // A poller that fails stays down until the trade is listed again; the screens still poll.
                            bestEffort("Following an Invest trade failed") {
                                if (trade.isSale) {
                                    sells.observeSell(trade.depositAddress).collect()
                                } else {
                                    buys.observeBuy(trade.depositAddress).collect()
                                }
                            }
                        }
                }
            }
        }
}
