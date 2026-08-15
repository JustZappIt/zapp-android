package co.electriccoin.zcash.ui.screen.settings.p2p

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.P2pProvider
import co.electriccoin.zcash.ui.common.repository.PeerCashOutRun
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.swap.peer.displayName
import co.electriccoin.zcash.ui.screen.swap.peer.order.buyerLegs
import co.electriccoin.zcash.ui.screen.swap.peer.order.orderFacts
import co.electriccoin.zcash.ui.screen.swap.peer.rowSubtitle
import xyz.justzappit.offramp.p2p.Usdc6
import xyz.justzappit.offramp.peer.PeerCashOutStatus
import xyz.justzappit.offramp.peer.PeerNetworkConfig
import xyz.justzappit.offramp.peer.PeerOrderPhase
import xyz.justzappit.offramp.peer.PeerOrderSnapshot

/**
 * A cash-out rendered as one more row in the activity list. It is the same card a merchant payment
 * gets: the rail replaces the order type, the phase replaces the order status, and the order's own
 * actions live in the expanded panel.
 */
internal fun PeerOrderSnapshot.toActivityRow(
    network: PeerNetworkConfig,
    nowSeconds: Long,
    isBusy: Boolean,
    onWithdraw: () -> Unit,
    onToggleMatching: () -> Unit,
): P2pTransactionRow =
    P2pTransactionRow(
        key = CASH_OUT_KEY_PREFIX + id.composite,
        provider = P2pProvider.PEER,
        logo = platform?.logo(),
        typeLabel = cashOutTypeLabel(this),
        statusLabel = stringRes(phaseStatusRes(phase)),
        statusTone = phaseTone(phase),
        amountUsdc = usdcAmount(grossAmount),
        amountSecondary =
            currencies
                .mapNotNull { it.currency }
                .takeIf { it.isNotEmpty() }
                ?.let { list -> stringRes(list.joinToString(CURRENCY_SEPARATOR) { it.code }) },
        reference = stringRes(R.string.p2p_transactions_row_deposit_id, id.onchain),
        referenceUrl = network.orderUrl(id),
        timestamp = openedAtSeconds?.let { stringRes(P2pTransactionsFormat.timestamp(it)) },
        detail =
            TransactionDetail(
                rows = orderDetailRows(network, nowSeconds),
                actions = orderActions(isBusy, onWithdraw, onToggleMatching),
            ),
    )

/**
 * An attempt that has not reached the chain yet. It exists on no indexer, so a list built only from
 * the chain shows nothing at exactly the moment the user goes looking for what they just started.
 */
internal fun PeerCashOutRun.toActivityRow(onOpen: () -> Unit): P2pTransactionRow {
    val failed = latest is PeerCashOutStatus.Failed
    return P2pTransactionRow(
        key = CASH_OUT_KEY_PREFIX + id.value,
        provider = P2pProvider.PEER,
        logo = platform.logo(),
        typeLabel = stringRes(R.string.p2p_transactions_type_cash_out, platform.displayName()),
        statusLabel =
            stringRes(
                if (failed) R.string.p2p_transactions_status_failed else R.string.p2p_transactions_status_in_progress,
            ),
        statusTone =
            if (failed) P2pTransactionRow.StatusTone.Failed else P2pTransactionRow.StatusTone.Pending,
        amountUsdc = usdcAmount(amount),
        amountSecondary =
            currencies
                .takeIf { it.isNotEmpty() }
                ?.let { list -> stringRes(list.joinToString(CURRENCY_SEPARATOR) { it.code }) },
        reference = null,
        referenceUrl = null,
        timestamp = null,
        detail =
            TransactionDetail(
                rows =
                    listOf(
                        TransactionDetailRow(
                            label = stringRes(R.string.p2p_transactions_detail_step),
                            value = rowSubtitle(),
                        ),
                    ),
                actions =
                    listOf(
                        ButtonState(
                            text = stringRes(R.string.p2p_transactions_action_view_progress),
                            onClick = onOpen,
                        ),
                    ),
            ),
    )
}

private fun cashOutTypeLabel(snapshot: PeerOrderSnapshot) =
    snapshot.platform
        ?.let { stringRes(R.string.p2p_transactions_type_cash_out, it.displayName()) }
        ?: stringRes(R.string.p2p_transactions_type_cash_out_unknown_rail)

private fun PeerOrderSnapshot.orderDetailRows(network: PeerNetworkConfig, nowSeconds: Long) =
    orderFacts(network = network, payee = null, nowSeconds = nowSeconds)
        .map { TransactionDetailRow(label = it.label, value = it.value, url = it.url) } +
        buyerLegs(network = network, nowSeconds = nowSeconds).map {
            TransactionDetailRow(
                label = stringRes(R.string.peer_order_buyer_detail_label, it.title),
                value = it.subtitle,
                url = it.url,
            )
        }

private fun PeerOrderSnapshot.orderActions(
    isBusy: Boolean,
    onWithdraw: () -> Unit,
    onToggleMatching: () -> Unit,
) = buildList {
    when {
        offersWithdrawal -> {
            add(
                ButtonState(
                    text = stringRes(R.string.peer_order_withdraw),
                    isEnabled = !isBusy,
                    isLoading = isBusy,
                    onClick = onWithdraw,
                ),
            )
        }

        offersMatchingToggle -> {
            add(
                ButtonState(
                    text =
                        stringRes(
                            if (acceptingIntents) {
                                R.string.peer_order_stop_matching
                            } else {
                                R.string.peer_order_start_matching
                            },
                        ),
                    isEnabled = !isBusy,
                    isLoading = isBusy,
                    onClick = onToggleMatching,
                ),
            )
        }

        else -> {
            Unit
        }
    }
}

private fun phaseStatusRes(phase: PeerOrderPhase) =
    when (phase) {
        PeerOrderPhase.WAITING -> R.string.p2p_transactions_status_waiting
        PeerOrderPhase.BUYER_PAYING -> R.string.p2p_transactions_status_selling
        PeerOrderPhase.PARTLY_SOLD -> R.string.p2p_transactions_status_partly_sold
        PeerOrderPhase.SOLD -> R.string.p2p_transactions_status_sold
        PeerOrderPhase.PAUSED -> R.string.p2p_transactions_status_paused
        PeerOrderPhase.CLOSED -> R.string.p2p_transactions_status_closed
    }

private fun phaseTone(phase: PeerOrderPhase) =
    when (phase) {
        PeerOrderPhase.SOLD -> P2pTransactionRow.StatusTone.Success
        PeerOrderPhase.CLOSED -> P2pTransactionRow.StatusTone.Cancelled
        else -> P2pTransactionRow.StatusTone.Pending
    }

private fun usdcAmount(amount: Usdc6) =
    stringRes(
        R.string.p2p_transactions_row_amount_usdc,
        amount.toDisplayString(stripTrailingZeros = true),
    )

private const val CASH_OUT_KEY_PREFIX = "cashout:"
private const val CURRENCY_SEPARATOR = ", "
