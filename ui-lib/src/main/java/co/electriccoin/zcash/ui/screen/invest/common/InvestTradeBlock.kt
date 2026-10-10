package co.electriccoin.zcash.ui.screen.invest.common

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.invest.model.InvestAsset
import co.electriccoin.zcash.ui.common.invest.model.PendingTrade
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.invest.progress.InvestProgressArgs
import co.electriccoin.zcash.ui.screen.invest.sellprogress.InvestSellProgressArgs

/** Where a pending trade is followed: I7 for a buy, I10 for a sale (each with support and Dismiss if stuck). */
internal fun PendingTrade.progressRoute(): Any =
    if (isSale) {
        InvestSellProgressArgs(depositAddress = depositAddress, assetId = assetId)
    } else {
        InvestProgressArgs(depositAddress = depositAddress, assetId = assetId)
    }

/**
 * Why a stock can't be traded right now, from [co.electriccoin.zcash.ui.common.invest.repository.InvestRepository]
 * `pendingTrades`: a buy or sale of it is still pending (stuck ones until dismissed), or the trade records can't be
 * read at all, which stops every trade.
 *
 * A second buy of a stock while one is pending is blocked on purpose too, though the engine would allow it: the
 * user follows one trade per stock at a time, and a stuck buy is dealt with before another is sent.
 */
internal sealed interface TradeBlock {
    data class Pending(
        val trade: PendingTrade,
    ) : TradeBlock

    data object RecordsUnreadable : TradeBlock

    companion object {
        /** What blocks [asset], given `pendingTrades` (null when the records can't be read). */
        fun of(
            trades: List<PendingTrade>?,
            asset: InvestAsset,
        ): TradeBlock? =
            if (trades == null) {
                RecordsUnreadable
            } else {
                trades.firstOrNull { it.assetId == asset.assetId }?.let(::Pending)
            }
    }
}

/**
 * The note shown where Buy or Sell is off, with the way forward: "A trade of NVIDIA is in progress" opens that
 * trade; "Invest can't read its trade records right now" opens support.
 */
internal data class InvestTradeInProgressState(
    val text: StringResource,
    val actionLabel: StringResource,
    val onOpen: () -> Unit,
)

internal fun TradeBlock.toState(
    asset: InvestAsset,
    navigationRouter: NavigationRouter,
): InvestTradeInProgressState =
    when (this) {
        is TradeBlock.Pending -> {
            InvestTradeInProgressState(
                text = stringRes(R.string.invest_trade_in_flight, asset.name),
                actionLabel = stringRes(R.string.invest_trade_see_it),
            ) { navigationRouter.forward(trade.progressRoute()) }
        }

        TradeBlock.RecordsUnreadable -> {
            unreadableRecordsState(navigationRouter)
        }
    }

internal fun unreadableRecordsState(navigationRouter: NavigationRouter) =
    InvestTradeInProgressState(
        text = stringRes(R.string.invest_trades_unreadable),
        actionLabel = stringRes(R.string.invest_contact_support),
    ) { InvestSupport.contact(navigationRouter, InvestSupport.Kind.RECORDS, reference = null) }
