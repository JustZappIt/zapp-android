package co.electriccoin.zcash.ui.screen.invest.common

import co.electriccoin.zcash.ui.common.invest.model.InvestAsset
import co.electriccoin.zcash.ui.common.invest.model.PendingTrade
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/** What a failed Confirm means for the review sheet. */
internal sealed interface ExecuteFailure {
    /** The prompt was cancelled; nothing was sent. */
    data object Cancelled : ExecuteFailure

    /** The engine recorded this trade, so it may have gone out: follow it rather than offer to pay again. */
    data class OursPending(
        val trade: PendingTrade,
    ) : ExecuteFailure

    /** The engine refused to sign; nothing was sent. */
    data object Refused : ExecuteFailure

    /** Not recorded and the price lapsed; nothing was sent. */
    data object Expired : ExecuteFailure

    /** Not recorded, as another trade of the stock is pending; nothing was sent. */
    data object OtherTradePending : ExecuteFailure

    /** The records can't be read, so whether this trade went out is unknown. */
    data object RecordsUnreadable : ExecuteFailure

    /** Not recorded, for no known reason. */
    data object Failed : ExecuteFailure

    companion object {
        /** A send can fail after broadcasting, so only a failure the engine didn't record means nothing was sent. */
        suspend fun classify(
            isCancelled: Boolean,
            isRefused: Boolean,
            isExpired: Boolean,
            ourDeposit: String,
            asset: InvestAsset,
            pendingTrades: Flow<List<PendingTrade>?>,
        ): ExecuteFailure {
            if (isCancelled) return Cancelled
            val trades = investCatching { pendingTrades.first() }.getOrNull()
            val ours = trades?.firstOrNull { it.depositAddress == ourDeposit }
            return when {
                trades == null -> RecordsUnreadable
                ours != null -> OursPending(ours)
                isRefused -> Refused
                isExpired -> Expired
                trades.any { it.assetId == asset.assetId } -> OtherTradePending
                else -> Failed
            }
        }
    }
}
