package co.electriccoin.zcash.ui.screen.settings.p2p

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.P2pProvider
import co.electriccoin.zcash.ui.common.repository.BaseBalance
import co.electriccoin.zcash.ui.common.repository.BaseBalanceRepository
import co.electriccoin.zcash.ui.common.repository.PeerCashOutRepository
import co.electriccoin.zcash.ui.common.repository.PeerCashOutRun
import co.electriccoin.zcash.ui.common.repository.PeerOrderActionRun
import co.electriccoin.zcash.ui.common.usecase.GetOfframpBaseAddressUseCase
import co.electriccoin.zcash.ui.common.usecase.GetP2pOrderHistoryUseCase
import co.electriccoin.zcash.ui.common.usecase.GetPeerOrderHistoryUseCase
import co.electriccoin.zcash.ui.common.usecase.ObservePeerCommittedUsdcUseCase
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.zapp.ZappConfirmationState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.ellipsizeMiddle
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.swap.peer.progress.PeerCashOutProgressArgs
import co.electriccoin.zcash.ui.screen.swap.peer.userMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.justzappit.evm.types.ChainId
import xyz.justzappit.offramp.config.P2pNetworkConfig
import xyz.justzappit.offramp.orchestrator.OfframpDriver
import xyz.justzappit.offramp.orchestrator.OfframpStatus
import xyz.justzappit.offramp.p2p.OrderStatus
import xyz.justzappit.offramp.p2p.P2pOrderHistoryItem
import xyz.justzappit.offramp.p2p.Usdc6
import xyz.justzappit.offramp.peer.PeerCashOutStatus
import xyz.justzappit.offramp.peer.PeerConfigProvider
import xyz.justzappit.offramp.peer.PeerDepositId
import xyz.justzappit.offramp.peer.PeerNetworkConfig
import xyz.justzappit.offramp.peer.PeerOrderSnapshot

/** One list for everything the Base account has done, whichever rail did it. */
@Suppress("TooManyFunctions")
internal class P2pTransactionsVM(
    private val navigationRouter: NavigationRouter,
    private val network: P2pNetworkConfig,
    private val baseBalance: BaseBalanceRepository,
    private val getBaseAddress: GetOfframpBaseAddressUseCase,
    private val getHistory: GetP2pOrderHistoryUseCase,
    private val getPeerHistory: GetPeerOrderHistoryUseCase,
    private val peerConfigProvider: PeerConfigProvider,
    private val peerRepository: PeerCashOutRepository,
    private val observeCommitted: ObservePeerCommittedUsdcUseCase,
    private val driver: OfframpDriver,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val address = MutableStateFlow<String?>(null)
    private val history = MutableStateFlow<HistoryResult>(HistoryResult.Loading)
    private val peerOrders = MutableStateFlow(PeerOrdersRead(orders = emptyList(), readAtMillis = null))
    private val isRefreshing = MutableStateFlow(false)
    private val refundFlow = MutableStateFlow<RefundFlow>(RefundFlow.Idle)
    private val interaction = MutableStateFlow(Interaction())
    private val refreshLock = Mutex()

    // Read at confirm time as well as rendered, because the dialog can be open while a cash-out
    // starts and the refund moves the whole balance either way.
    private val committed =
        observeCommitted().stateIn(viewModelScope, SharingStarted.Eagerly, Usdc6.ZERO)
    private val account: Flow<AccountRead> =
        combine(address, baseBalance.balance, committed, ::AccountRead)

    val state: StateFlow<P2pTransactionsState> =
        combine(
            combine(account, history, peerOrders, peerRepository.runs, peerRepository.orderActions, ::Sources),
            interaction,
            isRefreshing,
            refundFlow,
        ) { sources, interact, refreshing, rf ->
            buildState(sources, interact, refreshing, rf)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue =
                buildState(
                    sources =
                        Sources(
                            account =
                                AccountRead(
                                    address = null,
                                    balance = BaseBalance.Loading,
                                    committed = Usdc6.ZERO,
                                ),
                            history = HistoryResult.Loading,
                            peerOrders = PeerOrdersRead(orders = emptyList(), readAtMillis = null),
                            runs = emptyList(),
                            actions = emptyMap(),
                        ),
                    interact = Interaction(),
                    refreshing = false,
                    rf = RefundFlow.Idle,
                ),
        )

    init {
        refresh()
        // An attempt that settles becomes an order the indexer can answer for, and the balance it
        // escrowed has left the account. Dropping the current value leaves the initial read to init.
        viewModelScope.launch {
            peerRepository.runs
                .map { runs -> runs.count { it.holdsFunds } }
                .distinctUntilChanged()
                .drop(1)
                .collect { refresh() }
        }
        // An action that finished changed the order it acted on; the list it came from is a cached
        // indexer read, so re-read it rather than waiting for the user to pull.
        viewModelScope.launch {
            peerRepository.orderActions
                .map { current -> current.count { it.value.isRunning } }
                .distinctUntilChanged()
                .drop(1)
                .collect { refresh() }
        }
    }

    private fun refresh() {
        viewModelScope.launch { refreshNow() }
    }

    /** Serialised rather than dropped: a refresh that follows a withdrawal is the one showing its result. */
    private suspend fun refreshNow() =
        refreshLock.withLock {
            isRefreshing.update { true }
            try {
                if (address.value == null) address.update { runCatching { getBaseAddress() }.getOrNull() }
                baseBalance.refresh()
                val historyResult = getHistory()
                history.update {
                    if (historyResult == null) {
                        HistoryResult.Error
                    } else {
                        HistoryResult.Loaded(historyResult)
                    }
                }
                peerOrders.update { PeerOrdersRead(orders = getPeerHistory(), readAtMillis = nowMillis()) }
            } finally {
                isRefreshing.update { false }
            }
        }

    private fun onRefundClick() {
        if (refundFlow.value is RefundFlow.InProgress || committed.value > Usdc6.ZERO) return
        refundFlow.update { RefundFlow.Confirming }
    }

    private fun onDismissConfirm() {
        if (refundFlow.value !is RefundFlow.Confirming) return
        refundFlow.update { RefundFlow.Idle }
    }

    private fun onConfirmRefund() {
        if (refundFlow.value is RefundFlow.InProgress) return
        // A cash-out that started while this dialog was open has a claim on the balance this is
        // about to move in full.
        if (committed.value > Usdc6.ZERO) {
            refundFlow.update { RefundFlow.Idle }
            return
        }
        refundFlow.update { RefundFlow.InProgress }
        viewModelScope.launch {
            // orderId = null: skip the per-order cleanup path; the orchestrator just transfers the
            // smart-account USDC balance to the NEAR pullback target (mainnet) or noops (testnet).
            driver.bridgeFundsBackToZec(orderId = null).collect { status ->
                when (status) {
                    is OfframpStatus.FundsRecovered -> {
                        // Balance first: releasing the flow re-arms the refund button, and until the
                        // new balance lands the screen still shows the funds this one already moved.
                        baseBalance.refresh()
                        refundFlow.update { RefundFlow.Idle }
                    }

                    is OfframpStatus.Failed -> {
                        refundFlow.update { RefundFlow.Failed(status.message) }
                    }

                    else -> {
                        Unit
                    }
                }
            }
        }
    }

    private fun buildState(
        sources: Sources,
        interact: Interaction,
        refreshing: Boolean,
        rf: RefundFlow,
    ): P2pTransactionsState {
        val rows = activityRows(sources, interact)
        val confirmDialog =
            (rf as? RefundFlow.Confirming)?.let {
                ConfirmRefundDialog(
                    amount = formatBalanceAmount(sources.account.balance),
                    onConfirm = ::onConfirmRefund,
                    onDismiss = ::onDismissConfirm,
                )
            }
        return P2pTransactionsState(
            onBack = ::onBack,
            onRefresh = ::refresh,
            isRefreshing = refreshing,
            balance = balanceUi(sources.account),
            refund = refundUi(sources.account, rf),
            confirmRefund = confirmDialog,
            filter = filterUi(interact.filter),
            rows = rows,
            emptyMessage =
                if (sources.history is HistoryResult.Loaded && rows.isEmpty()) {
                    stringRes(R.string.p2p_transactions_empty)
                } else {
                    null
                },
            errorMessage =
                sources.actionFailure
                    ?: stringRes(R.string.p2p_transactions_error).takeIf { sources.history is HistoryResult.Error },
            confirmation = interact.confirmation,
        )
    }

    /**
     * Open first, then newest. A cash-out carries no wall-clock stamp until a buyer touches it: an
     * undated open one is either brand new or still waiting, so it sorts as the most recent, while an
     * undated finished one is an order nobody ever bought and sorts as the oldest rather than
     * planting itself above every payment made since.
     */
    private fun activityRows(sources: Sources, interact: Interaction): List<P2pTransactionRow> {
        val pay =
            (sources.history as? HistoryResult.Loaded)
                ?.items
                .orEmpty()
                .map { Activity(it.toRow(network), it.isOpen, it.atEpochSeconds) }
        val cashOut = peerActivity(sources) + sources.runs.filter { it.isUnindexed }.map(::inFlightActivity)
        return (pay + cashOut)
            .filter { interact.filter.keeps(it.row.provider) }
            .sortedWith(
                compareByDescending<Activity> { it.isOpen }
                    .thenByDescending { it.sortKey },
            ).map { it.row }
    }

    private fun peerActivity(sources: Sources): List<Activity> {
        val peerNetwork = peerConfigProvider.currentOrNull() ?: return emptyList()
        val readAtMillis = sources.peerOrders.readAtMillis
        return sources.peerOrders.orders.map { snapshot ->
            Activity(
                row =
                    peerRow(
                        snapshot = snapshot,
                        peerNetwork = peerNetwork,
                        isBusy = sources.actions[snapshot.id]?.awaitsConfirmation(readAtMillis) == true,
                    ),
                isOpen = !snapshot.phase.isFinished,
                atEpochSeconds = snapshot.atEpochSeconds,
            )
        }
    }

    private fun peerRow(snapshot: PeerOrderSnapshot, peerNetwork: PeerNetworkConfig, isBusy: Boolean) =
        snapshot.toActivityRow(
            network = peerNetwork,
            nowSeconds = nowMillis() / MILLIS_PER_SECOND,
            isBusy = isBusy,
            onWithdraw = { onWithdrawClick(snapshot) },
            onToggleMatching = { onAcceptingToggle(snapshot.id, !snapshot.acceptingIntents) },
        )

    private fun inFlightActivity(run: PeerCashOutRun) =
        Activity(
            row = run.toActivityRow { navigationRouter.forward(PeerCashOutProgressArgs(cashOutId = run.id.value)) },
            isOpen = true,
            atEpochSeconds = run.startedAtMillis / MILLIS_PER_SECOND,
        )

    private fun filterUi(selected: P2pActivityFilter): FilterState? {
        if (!peerConfigProvider.isAvailable) return null
        return FilterState(
            options = P2pActivityFilter.entries,
            selected = selected,
            onSelect = { next -> interaction.update { it.copy(filter = next) } },
        )
    }

    private fun onWithdrawClick(snapshot: PeerOrderSnapshot) {
        val amount = snapshot.withdrawableAfterPrune
        interaction.update {
            it.copy(
                confirmation =
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
                                onClick = { runWithdraw(snapshot.id, amount) },
                            ),
                        secondaryButton =
                            ButtonState(
                                text = stringRes(R.string.peer_order_withdraw_confirm_cancel),
                                onClick = ::dismissConfirmation,
                            ),
                        onBack = ::dismissConfirmation,
                    ),
            )
        }
    }

    private fun runWithdraw(id: PeerDepositId, amount: Usdc6) {
        dismissConfirmation()
        peerRepository.clearOrderAction(id)
        peerRepository.withdraw(id, amount)
    }

    private fun onAcceptingToggle(id: PeerDepositId, accepting: Boolean) {
        peerRepository.clearOrderAction(id)
        peerRepository.setAcceptingIntents(id, accepting)
    }

    private fun dismissConfirmation() {
        interaction.update { it.copy(confirmation = null) }
    }

    private fun balanceUi(read: AccountRead): BalanceState =
        when (read.balance) {
            BaseBalance.Loading -> {
                BalanceState.Loading
            }

            BaseBalance.Unavailable -> {
                BalanceState.Unavailable
            }

            is BaseBalance.Loaded -> {
                read.address?.let { address ->
                    BalanceState.Loaded(
                        balanceUsdc =
                            stringRes(
                                R.string.p2p_transactions_balance_amount,
                                read.balance.balance.toDisplayString(stripTrailingZeros = true)
                            ),
                        accountAddressShort =
                            address.ellipsizeMiddle(
                                prefix = ADDRESS_ELLIPSIS_PREFIX,
                                suffix = ADDRESS_ELLIPSIS_SUFFIX
                            ),
                        accountExplorerUrl = network.addressUrl(address),
                    )
                } ?: BalanceState.Loading
            }
        }

    /**
     * The refund moves the whole account balance, and a cash-out that is validating, approving or
     * waiting to broadcast still has its USDC sitting in it. There is no partial refund to offer,
     * so while an attempt reserves any of the balance the control says so rather than moving coins
     * out from under it.
     */
    private fun refundUi(account: AccountRead, rf: RefundFlow): RefundUiState {
        // Refund route exists only on mainnet (testnet has no NEAR target).
        val available = account.balance.loadedOrNull.takeIf { network.chainId == ChainId.BASE_MAINNET }
        return when {
            available == null || available <= Usdc6.ZERO -> {
                RefundUiState.Hidden
            }

            account.committed > Usdc6.ZERO && rf !is RefundFlow.InProgress -> {
                RefundUiState.Blocked(
                    reason =
                        stringRes(
                            R.string.p2p_transactions_refund_blocked,
                            account.committed.toDisplayString(stripTrailingZeros = true),
                        ),
                )
            }

            else -> {
                refundForFlow(rf)
            }
        }
    }

    private fun refundForFlow(rf: RefundFlow): RefundUiState =
        when (rf) {
            RefundFlow.Idle, RefundFlow.Confirming -> {
                RefundUiState.Available(onClick = ::onRefundClick)
            }

            RefundFlow.InProgress -> {
                RefundUiState.InProgress
            }

            is RefundFlow.Failed -> {
                RefundUiState.FailedRetry(
                    message = stringRes(R.string.p2p_transactions_refund_failed, rf.message),
                    onRetry = ::onRefundClick,
                )
            }
        }

    private fun formatBalanceAmount(bal: BaseBalance) =
        stringRes(
            R.string.p2p_transactions_balance_amount,
            (bal.loadedOrNull ?: Usdc6.ZERO).toDisplayString(stripTrailingZeros = true),
        )

    private fun onBack() = navigationRouter.back()

    /** Stamped, because an action that has settled is only safe to re-offer once a later read lands. */
    private data class PeerOrdersRead(
        val orders: List<PeerOrderSnapshot>,
        val readAtMillis: Long?,
    )

    private data class AccountRead(
        val address: String?,
        val balance: BaseBalance,
        /** USDC promised to cash-outs that have not escrowed it yet, so still inside [balance]. */
        val committed: Usdc6,
    )

    private data class Sources(
        val account: AccountRead,
        val history: HistoryResult,
        val peerOrders: PeerOrdersRead,
        val runs: List<PeerCashOutRun>,
        val actions: Map<PeerDepositId, PeerOrderActionRun>,
    ) {
        val actionFailure: StringResource?
            get() =
                actions.values
                    .firstNotNullOfOrNull { it.failure }
                    ?.error
                    ?.userMessage()
    }

    private data class Interaction(
        val filter: P2pActivityFilter = P2pActivityFilter.ALL,
        val confirmation: ZappConfirmationState? = null,
    )

    private data class Activity(
        val row: P2pTransactionRow,
        val isOpen: Boolean,
        val atEpochSeconds: Long?,
    ) {
        val sortKey: Long get() = atEpochSeconds ?: if (isOpen) Long.MAX_VALUE else Long.MIN_VALUE
    }

    private sealed interface HistoryResult {
        data object Loading : HistoryResult

        data object Error : HistoryResult

        data class Loaded(
            val items: List<P2pOrderHistoryItem>
        ) : HistoryResult
    }

    private sealed interface RefundFlow {
        data object Idle : RefundFlow

        data object Confirming : RefundFlow

        data object InProgress : RefundFlow

        data class Failed(
            val message: String
        ) : RefundFlow
    }

    companion object {
        private const val ADDRESS_ELLIPSIS_PREFIX = 8
        private const val ADDRESS_ELLIPSIS_SUFFIX = 4
        private const val MILLIS_PER_SECOND = 1_000L
    }
}

private fun P2pActivityFilter.keeps(provider: P2pProvider): Boolean =
    when (this) {
        P2pActivityFilter.ALL -> true
        P2pActivityFilter.PEER -> provider == P2pProvider.PEER
        P2pActivityFilter.P2P_ME -> provider == P2pProvider.P2P_ME
    }

private val P2pOrderHistoryItem.isOpen: Boolean
    get() = status != OrderStatus.COMPLETED && status != OrderStatus.CANCELLED

private val P2pOrderHistoryItem.atEpochSeconds: Long?
    get() = completedAtEpochSeconds ?: cancelledAtEpochSeconds ?: placedAtEpochSeconds

private val PeerOrderSnapshot.atEpochSeconds: Long?
    get() =
        intents.mapNotNull { it.fulfillTimestampSeconds ?: it.signalTimestampSeconds }.maxOrNull()
            ?: openedAtSeconds
