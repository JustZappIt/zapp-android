package co.electriccoin.zcash.ui.screen.swap.peer.order

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.settings.p2p.P2pTransactionsFormat
import co.electriccoin.zcash.ui.screen.swap.peer.displayName
import xyz.justzappit.offramp.p2p.Usdc6
import xyz.justzappit.offramp.peer.PayeeHandle
import xyz.justzappit.offramp.peer.PeerNetworkConfig
import xyz.justzappit.offramp.peer.PeerOrderSnapshot

/**
 * One account of an order, rendered by both the order screen and the Base activity dropdown. The two
 * surfaces described the same deposit differently while they each built their own rows.
 */
internal fun PeerOrderSnapshot.orderFacts(
    network: PeerNetworkConfig,
    payee: PayeeHandle?,
    nowSeconds: Long,
): List<PeerOrderRow> = timingFacts(nowSeconds) + moneyFacts() + termsFacts(payee) + provenanceFacts(network)

private fun PeerOrderSnapshot.timingFacts(nowSeconds: Long): List<PeerOrderRow> =
    buildList {
        openedAtSeconds?.let {
            row(R.string.peer_order_row_opened, stringRes(P2pTransactionsFormat.timestamp(it)))
        }
        P2pTransactionsFormat.duration(openForSeconds(nowSeconds))?.let {
            row(
                if (phase.isFinished) R.string.peer_order_row_ran_for else R.string.peer_order_row_open_for,
                stringRes(it),
            )
        }
        P2pTransactionsFormat.duration(secondsToFirstBuyer)?.let {
            row(R.string.peer_order_row_first_buyer, stringRes(it))
        }
    }

private fun PeerOrderSnapshot.moneyFacts(): List<PeerOrderRow> =
    buildList {
        if (soldAmount > Usdc6.ZERO) {
            row(
                R.string.peer_order_row_sold,
                stringRes(R.string.peer_order_sold_progress, soldAmount.display(), grossAmount.display()),
            )
        }
        if (!phase.isFinished) {
            row(R.string.peer_order_row_on_offer, usdcAmount(remaining))
            row(R.string.peer_order_row_free_to_withdraw, usdcAmount(withdrawableAfterPrune))
        }
        if (totalWithdrawn > Usdc6.ZERO) {
            row(R.string.peer_order_row_returned, usdcAmount(totalWithdrawn))
        }
    }

private fun PeerOrderSnapshot.termsFacts(payee: PayeeHandle?): List<PeerOrderRow> =
    buildList {
        offerRate()?.let { row(R.string.peer_order_row_rate, it) }
        currencyCodes()?.let { row(R.string.peer_order_row_currencies, stringRes(it)) }
        takeRange()?.let { row(R.string.peer_order_row_buyer_take, it) }
        buyerTally()?.let { row(R.string.peer_order_row_buyers, it) }
        platform?.let { platform ->
            row(
                R.string.peer_order_row_paid_to,
                payee
                    ?.let { stringRes(R.string.peer_order_paid_to_value, platform.displayName(), it.value) }
                    ?: platform.displayName(),
            )
        }
    }

private fun PeerOrderSnapshot.provenanceFacts(network: PeerNetworkConfig): List<PeerOrderRow> =
    buildList {
        creationTxHash?.let {
            row(
                label = R.string.peer_order_row_opened_on_base,
                value = stringRes(it.hex.shortHash()),
                url = network.txUrl(it.hex),
            )
        }
    }

/**
 * Read off the deposit, never recomputed. Quoted only when a single currency owns the rate: a
 * multi-currency order carries one per rail and has no single answer.
 */
private fun PeerOrderSnapshot.offerRate(): StringResource? =
    currencies.singleOrNull()?.let { entry ->
        val currency = entry.currency
        val rate = entry.oracleRate
        if (currency == null || rate == null) {
            null
        } else {
            stringRes(R.string.peer_offramp_rate_value, rate.display(currency), currency.code)
        }
    }

private fun PeerOrderSnapshot.currencyCodes(): String? =
    currencies
        .mapNotNull { it.currency }
        .takeIf { it.isNotEmpty() }
        ?.joinToString(CURRENCY_SEPARATOR) { it.code }

/** Named rather than hidden when the range collapses: that is the case the user has to act on. */
private fun PeerOrderSnapshot.takeRange(): StringResource? =
    when {
        intentAmountMin <= Usdc6.ZERO -> null
        isAllOrNothing -> stringRes(R.string.peer_order_take_whole, intentAmountMax.display())
        else -> stringRes(R.string.peer_order_take_range, intentAmountMin.display(), intentAmountMax.display())
    }

private fun PeerOrderSnapshot.buyerTally(): StringResource? {
    if (totalIntents <= 0) return null
    return stringRes(R.string.peer_order_buyers_tally, totalIntents, fulfilledIntents, prunedIntents)
}

private fun MutableList<PeerOrderRow>.row(label: Int, value: StringResource, url: String? = null) {
    add(PeerOrderRow(label = stringRes(label), value = value, url = url))
}

private const val CURRENCY_SEPARATOR = ", "
