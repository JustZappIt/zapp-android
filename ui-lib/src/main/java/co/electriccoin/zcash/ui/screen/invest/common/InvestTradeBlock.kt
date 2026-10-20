package co.electriccoin.zcash.ui.screen.invest.common

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.invest.model.InvestAsset
import co.electriccoin.zcash.ui.common.invest.model.PendingTrade
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.invest.progress.InvestProgressArgs
import co.electriccoin.zcash.ui.screen.invest.sellprogress.InvestSellProgressArgs

internal fun PendingTrade.progressRoute(): Any =
    if (isSale) {
        InvestSellProgressArgs(depositAddress = depositAddress, assetId = assetId)
    } else {
        InvestProgressArgs(depositAddress = depositAddress, assetId = assetId)
    }

/** Why a stock can't be traded right now: a trade of it is pending, or the records can't be read. */
internal sealed interface TradeBlock {
    data class Pending(
        val trade: PendingTrade,
    ) : TradeBlock

    data object RecordsUnreadable : TradeBlock

    companion object {
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
