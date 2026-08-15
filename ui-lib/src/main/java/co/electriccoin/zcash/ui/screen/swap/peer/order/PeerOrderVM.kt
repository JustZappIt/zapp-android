package co.electriccoin.zcash.ui.screen.swap.peer.order

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.provider.PeerPayeeHandleProvider
import co.electriccoin.zcash.ui.common.repository.PeerCashOutRepository
import co.electriccoin.zcash.ui.common.repository.PeerOrderActionRun
import co.electriccoin.zcash.ui.common.usecase.ObservePeerOrderUseCase
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.zapp.ZappConfirmationState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.settings.p2p.P2pTransactionsArgs
import co.electriccoin.zcash.ui.screen.swap.peer.displayName
import co.electriccoin.zcash.ui.screen.swap.peer.userMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.justzappit.offramp.p2p.Usdc6
import xyz.justzappit.offramp.peer.PayeeHandle
import xyz.justzappit.offramp.peer.PeerCashOutStatus
import xyz.justzappit.offramp.peer.PeerDepositId
import xyz.justzappit.offramp.peer.PeerNetworkConfig
import xyz.justzappit.offramp.peer.PeerNetworks
import xyz.justzappit.offramp.peer.PeerOrderPhase
import xyz.justzappit.offramp.peer.PeerOrderSnapshot

/**
 * The waiting surface, and the durable one. Everything it renders comes from [PeerDepositId] plus
 * one indexer read, so a cold start after process death, a reboot, or a reinstall on the same seed
 * brings back the same screen. If any part of it needed a value that only exists in memory, that
 * would be a bug rather than a design choice.
 */
@Suppress("TooManyFunctions")
internal class PeerOrderVM(
    private val navigationRouter: NavigationRouter,
    private val observeOrder: ObservePeerOrderUseCase,
    private val peerCashOutRepository: PeerCashOutRepository,
    private val payeeHandleProvider: PeerPayeeHandleProvider,
    private val network: PeerNetworkConfig,
    private val depositId: PeerDepositId,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val snapshot = MutableStateFlow<PeerOrderSnapshot?>(null)
    private val payee = MutableStateFlow<PayeeHandle?>(null)
    private val lastReadMillis = MutableStateFlow<Long?>(null)
    private val error = MutableStateFlow<StringResource?>(null)
    private val confirmation = MutableStateFlow<ZappConfirmationState?>(null)
    private var isPayeeResolved = false

    // The action runs on the application scope, so leaving and re-entering this screen shows the
    // same withdrawal still going rather than offering to start a second one.
    private val action = peerCashOutRepository.orderActions.map { it[depositId] }

    init {
        viewModelScope.launch {
            observeOrder(depositId).collect(::onStatus)
        }
    }

    val state: StateFlow<PeerOrderState> =
        combine(
            combine(snapshot, payee, ::OrderRead),
            lastReadMillis,
            error,
            action,
            confirmation,
        ) { read, readAt, err, running, confirm ->
            buildState(read, readAt, err ?: running?.failure?.error?.userMessage(), running, confirm)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue = buildState(OrderRead(null, null), null, null, null, null),
        )

    private suspend fun onStatus(status: PeerCashOutStatus) {
        when (status) {
            is PeerCashOutStatus.OrderLive -> {
                snapshot.update { status.snapshot }
                lastReadMillis.update { nowMillis() }
                error.update { null }
                resolvePayee(status.snapshot)
            }

            // A read failure is not an order failure: the last known state stays on screen with a
            // "last updated" stamp, and only a reverted transaction is rendered as a failure.
            is PeerCashOutStatus.Failed -> {
                error.update { status.error.userMessage() }
            }

            else -> {
                Unit
            }
        }
    }

    /**
     * The handle this order actually pays, or nothing. Stored handles are per platform and a user
     * can retype one between orders, so only the handle whose registered hash is the one the deposit
     * was opened against is named: any other is a different destination.
     */
    private suspend fun resolvePayee(snap: PeerOrderSnapshot) {
        // Guarded on "tried", not "found": the poll re-emits every few seconds, and a handle that
        // does not match is the normal case on a second device or after the handle was retyped.
        if (isPayeeResolved) return
        isPayeeResolved = true
        val record = snap.platform?.let { payeeHandleProvider.get(it) }
        if (record?.hash != null && record.hash == snap.payeeHash) payee.update { record.handle }
    }

    private fun buildState(
        read: OrderRead,
        readAtMillis: Long?,
        errorText: StringResource?,
        running: PeerOrderActionRun?,
        confirm: ZappConfirmationState?,
    ): PeerOrderState {
        val snap = read.snapshot
        val busy = running?.awaitsConfirmation(readAtMillis) == true
        return PeerOrderState(
            headline = headlineFor(snap),
            supporting = errorText ?: supportingFor(snap),
            soldProgress =
                snap
                    ?.takeIf { it.soldAmount > Usdc6.ZERO }
                    ?.let {
                        stringRes(
                            R.string.peer_order_sold_progress,
                            it.soldAmount.toDisplayString(stripTrailingZeros = true),
                            it.grossAmount.toDisplayString(stripTrailingZeros = true),
                        )
                    },
            rows = snap?.orderFacts(network, read.payee, nowSeconds()).orEmpty(),
            buyers = snap?.buyerLegs(network, nowSeconds()).orEmpty(),
            lastUpdated =
                readAtMillis?.let {
                    stringRes(R.string.peer_order_last_updated, minutesAgo(it).toInt())
                },
            primaryAction = primaryActionFor(snap, busy),
            secondaryAction = secondaryActionFor(snap),
            explorerUrl = network.orderUrl(depositId),
            confirmation = confirm,
            onBack = navigationRouter::back,
        )
    }

    private fun primaryActionFor(snap: PeerOrderSnapshot?, busy: Boolean): ButtonState? =
        when {
            snap == null -> {
                null
            }

            snap.offersWithdrawal -> {
                ButtonState(
                    text = stringRes(R.string.peer_order_withdraw),
                    isEnabled = !busy,
                    isLoading = busy,
                    onClick = { onWithdrawClick(snap) },
                )
            }

            snap.offersMatchingToggle -> {
                ButtonState(
                    text =
                        stringRes(
                            if (snap.acceptingIntents) {
                                R.string.peer_order_stop_matching
                            } else {
                                R.string.peer_order_start_matching
                            },
                        ),
                    isEnabled = !busy,
                    isLoading = busy,
                    onClick = { onAcceptingToggle(!snap.acceptingIntents) },
                )
            }

            else -> {
                null
            }
        }

    // Closing left USDC on Base, which no Zcash activity list can show, so the one screen that owns
    // that balance is reachable from here.
    private fun secondaryActionFor(snap: PeerOrderSnapshot?): ButtonState? =
        snap
            ?.takeIf { it.phase == PeerOrderPhase.CLOSED }
            ?.let {
                ButtonState(
                    text = stringRes(R.string.peer_order_view_base_account),
                    onClick = { navigationRouter.forward(P2pTransactionsArgs) },
                )
            }

    private fun headlineFor(snap: PeerOrderSnapshot?): StringResource =
        when (snap?.phase) {
            null -> stringRes(R.string.peer_order_headline_loading)
            PeerOrderPhase.WAITING -> stringRes(R.string.peer_order_headline_waiting)
            PeerOrderPhase.BUYER_PAYING -> stringRes(R.string.peer_order_headline_buyer_paying)
            PeerOrderPhase.PARTLY_SOLD -> stringRes(R.string.peer_order_headline_partially_sold)
            PeerOrderPhase.SOLD -> stringRes(R.string.peer_order_headline_sold)
            PeerOrderPhase.PAUSED -> stringRes(R.string.peer_order_headline_not_accepting)
            PeerOrderPhase.CLOSED -> stringRes(R.string.peer_order_headline_closed)
        }

    private fun supportingFor(snap: PeerOrderSnapshot?): StringResource? {
        val platform = snap?.platform ?: return null
        return when (snap.phase) {
            PeerOrderPhase.WAITING -> {
                when {
                    snap.isHiddenFromBuyers -> {
                        stringRes(R.string.peer_order_supporting_waiting_too_small, orderbookMinimum())
                    }

                    snap.isAllOrNothing -> {
                        stringRes(R.string.peer_order_supporting_waiting_whole)
                    }

                    else -> {
                        stringRes(R.string.peer_order_supporting_waiting)
                    }
                }
            }

            PeerOrderPhase.BUYER_PAYING -> {
                stringRes(R.string.peer_order_supporting_buyer_paying)
            }

            PeerOrderPhase.PARTLY_SOLD -> {
                if (snap.isHiddenFromBuyers) {
                    stringRes(R.string.peer_order_supporting_partially_sold_too_small, orderbookMinimum())
                } else {
                    stringRes(R.string.peer_order_supporting_partially_sold)
                }
            }

            PeerOrderPhase.SOLD -> {
                stringRes(R.string.peer_order_supporting_sold, platform.displayName())
            }

            PeerOrderPhase.PAUSED -> {
                stringRes(R.string.peer_order_supporting_not_accepting)
            }

            PeerOrderPhase.CLOSED -> {
                stringRes(R.string.peer_order_supporting_closed)
            }
        }
    }

    private fun onWithdrawClick(snap: PeerOrderSnapshot) {
        val amount = snap.withdrawableAfterPrune
        confirmation.update {
            ZappConfirmationState(
                title = stringRes(R.string.peer_order_withdraw_confirm_title),
                message =
                    stringRes(
                        R.string.peer_order_withdraw_confirm_message,
                        amount.toDisplayString(stripTrailingZeros = true),
                    ),
                primaryButton =
                    ButtonState(
                        text = stringRes(R.string.peer_order_withdraw_confirm_action),
                        onClick = { runWithdraw(amount) },
                    ),
                secondaryButton =
                    ButtonState(
                        text = stringRes(R.string.peer_order_withdraw_confirm_cancel),
                        onClick = ::dismissConfirmation,
                    ),
                onBack = ::dismissConfirmation,
            )
        }
    }

    private fun runWithdraw(amount: Usdc6) {
        dismissConfirmation()
        peerCashOutRepository.clearOrderAction(depositId)
        peerCashOutRepository.withdraw(depositId, amount)
    }

    private fun onAcceptingToggle(accepting: Boolean) {
        peerCashOutRepository.clearOrderAction(depositId)
        peerCashOutRepository.setAcceptingIntents(depositId, accepting)
    }

    private fun dismissConfirmation() {
        confirmation.update { null }
    }

    private fun minutesAgo(readAtMillis: Long): Long =
        ((nowMillis() - readAtMillis) / MILLIS_PER_MINUTE).coerceAtLeast(0)

    private fun nowSeconds(): Long = nowMillis() / MILLIS_PER_SECOND

    private fun orderbookMinimum(): String =
        Usdc6.ofMicros(PeerNetworks.ORDERBOOK_MIN_VISIBLE_MICROS).display()

    private data class OrderRead(
        val snapshot: PeerOrderSnapshot?,
        val payee: PayeeHandle?,
    )

    private companion object {
        const val MILLIS_PER_MINUTE = 60_000L
        const val MILLIS_PER_SECOND = 1_000L
    }
}
