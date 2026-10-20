package co.electriccoin.zcash.ui.common.invest.repository

import co.electriccoin.zcash.ui.common.bestEffort
import co.electriccoin.zcash.ui.common.invest.model.PendingTrade
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes

/**
 * Follows every pending trade to its final state without its progress screen open. Callers share one follower,
 * which stops when the last one leaves.
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

    private suspend fun observe(trade: PendingTrade) =
        if (trade.isSale) {
            sells.observeSell(trade.depositAddress).collect()
        } else {
            buys.observeBuy(trade.depositAddress).collect()
        }

    // One poller per pending trade. Unreadable records stop them all, and are read again after [RETRY_AFTER].
    private suspend fun follow(): Nothing {
        while (true) {
            coroutineScope {
                val pollers = mutableMapOf<String, Job>()
                buys.pendingTrades.collect { trades ->
                    val pending = trades.orEmpty().associateBy { it.depositAddress }
                    (pollers.keys - pending.keys).forEach { pollers.remove(it)?.cancel() }
                    pending.values.filter { it.depositAddress !in pollers }.forEach { trade ->
                        pollers[trade.depositAddress] =
                            launch {
                                while (!bestEffort("Following an Invest trade failed") { observe(trade) }) {
                                    delay(RETRY_AFTER)
                                }
                            }
                    }
                }
            }
            delay(RETRY_AFTER)
        }
    }

    private companion object {
        val RETRY_AFTER = 1.minutes
    }
}
