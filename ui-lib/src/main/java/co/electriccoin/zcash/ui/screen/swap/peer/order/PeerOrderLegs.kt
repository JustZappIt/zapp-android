package co.electriccoin.zcash.ui.screen.swap.peer.order

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.settings.p2p.P2pTransactionsFormat
import xyz.justzappit.offramp.peer.PeerIntent
import xyz.justzappit.offramp.peer.PeerIntentOutcome
import xyz.justzappit.offramp.peer.PeerNetworkConfig
import xyz.justzappit.offramp.peer.PeerOrderSnapshot

internal fun PeerOrderSnapshot.buyerLegs(
    network: PeerNetworkConfig,
    nowSeconds: Long,
): List<PeerOrderBuyerRow> =
    intentsNewestFirst.map { intent ->
        PeerOrderBuyerRow(
            title = stringRes(R.string.peer_order_buyer_amount, intent.amount.display()),
            subtitle = intent.legStatus(nowSeconds),
            tone = intent.outcome.tone(),
            url = intent.settlementTxHash?.let { network.txUrl(it.hex) },
        )
    }

/**
 * Read from the intent's own verified fields. Before the proof this is what the buyer owes, not what
 * the user has received, and the copy must not imply otherwise.
 */
private fun PeerIntent.legStatus(nowSeconds: Long): StringResource {
    val fiat = fiatOwed()
    return when (outcome) {
        PeerIntentOutcome.PAYING -> {
            P2pTransactionsFormat
                .duration(secondsLeftToPay(nowSeconds))
                ?.let { stringRes(R.string.peer_order_buyer_paying, fiat, it) }
                ?: stringRes(R.string.peer_order_buyer_owes, fiat)
        }

        PeerIntentOutcome.OUT_OF_TIME -> {
            stringRes(R.string.peer_order_buyer_out_of_time)
        }

        PeerIntentOutcome.PAID -> {
            P2pTransactionsFormat
                .duration(fillLatencySeconds?.toLong())
                ?.let { stringRes(R.string.peer_order_buyer_paid_in, fiat, it) }
                ?: stringRes(R.string.peer_order_buyer_paid, fiat)
        }

        PeerIntentOutcome.BACKED_OUT -> {
            P2pTransactionsFormat
                .duration(heldForSeconds)
                ?.let { stringRes(R.string.peer_order_buyer_backed_out_after, it) }
                ?: stringRes(R.string.peer_order_buyer_backed_out)
        }

        PeerIntentOutcome.TIMED_OUT -> {
            stringRes(R.string.peer_order_buyer_expired)
        }

        PeerIntentOutcome.UNKNOWN -> {
            stringRes("")
        }
    }
}

private fun PeerIntent.fiatOwed(): StringResource =
    paymentCurrency
        ?.let { stringRes(R.string.p2p_transactions_row_amount_fiat, paymentAmount.toDisplayString(it), it.code) }
        ?: stringRes("")

private fun PeerIntentOutcome.tone(): PeerBuyerTone =
    when (this) {
        PeerIntentOutcome.PAID -> PeerBuyerTone.Settled
        PeerIntentOutcome.PAYING, PeerIntentOutcome.OUT_OF_TIME -> PeerBuyerTone.Live
        PeerIntentOutcome.BACKED_OUT, PeerIntentOutcome.TIMED_OUT, PeerIntentOutcome.UNKNOWN -> PeerBuyerTone.Dropped
    }
