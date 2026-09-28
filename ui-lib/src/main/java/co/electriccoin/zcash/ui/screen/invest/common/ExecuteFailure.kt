package co.electriccoin.zcash.ui.screen.invest.common

import co.electriccoin.zcash.ui.common.invest.model.InvestAsset
import co.electriccoin.zcash.ui.common.invest.model.PendingTrade
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/** What a failed buy or sale confirm means for the review sheet. */
internal sealed interface ExecuteFailure {
    /** The prompt was cancelled: nothing was sent; the sheet stays as it was. */
    data object Cancelled : ExecuteFailure

    /** The engine lists this very trade: it may have gone out, so follow it rather than offer to pay again. */
    data class OursPending(
        val trade: PendingTrade,
    ) : ExecuteFailure

    /** The engine refused what it was asked to sign; nothing was sent. */
    data object Refused : ExecuteFailure

    /** Not recorded, and the price lapsed meanwhile: nothing was sent, a fresh price is the way on. */
    data object Expired : ExecuteFailure

    /** Not recorded, but another trade of the stock is pending: nothing was sent, and Confirm stays off. */
    data object OtherTradePending : ExecuteFailure

    /** Not recorded and no known reason: close the sheet and say it didn't complete. */
    data object Failed : ExecuteFailure

    companion object {
        /**
         * Everything but a cancelled prompt first asks the engine whether it recorded this trade ([ourDeposit]),
         * because a send can fail after broadcasting and still count. Only an unrecorded failure can be read as
         * "nothing sent" (refused, expired, blocked by another trade), so a lapsed price never invites paying twice.
         */
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
                ours != null -> OursPending(ours)
                isRefused -> Refused
                isExpired -> Expired
                trades?.any { it.assetId == asset.assetId } == true -> OtherTradePending
                else -> Failed
            }
        }
    }
}
