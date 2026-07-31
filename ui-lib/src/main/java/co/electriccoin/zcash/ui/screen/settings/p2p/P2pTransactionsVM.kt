package co.electriccoin.zcash.ui.screen.settings.p2p

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.usecase.GetP2pOrderHistoryUseCase
import co.electriccoin.zcash.ui.design.util.ellipsizeMiddle
import co.electriccoin.zcash.ui.design.util.stringRes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.justzappit.evm.rpc.BaseRpcClient
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.types.ChainId
import xyz.justzappit.offramp.account.SmartOfframpAccountProvider
import xyz.justzappit.offramp.config.P2pNetworkConfig
import xyz.justzappit.offramp.orchestrator.OfframpDriver
import xyz.justzappit.offramp.orchestrator.OfframpStatus
import xyz.justzappit.offramp.p2p.P2pOrderHistoryItem
import xyz.justzappit.offramp.p2p.Usdc6
import xyz.justzappit.offramp.p2p.getUsdcBalance

internal class P2pTransactionsVM(
    private val navigationRouter: NavigationRouter,
    private val network: P2pNetworkConfig,
    private val rpc: BaseRpcClient,
    private val accountProvider: SmartOfframpAccountProvider,
    private val getHistory: GetP2pOrderHistoryUseCase,
    private val driver: OfframpDriver,
) : ViewModel() {
    private val balance = MutableStateFlow<BalanceLoad>(BalanceLoad.Loading)
    private val history = MutableStateFlow<HistoryResult>(HistoryResult.Loading)
    private val isRefreshing = MutableStateFlow(false)
    private val refundFlow = MutableStateFlow<RefundFlow>(RefundFlow.Idle)

    val state: StateFlow<P2pTransactionsState> =
        combine(balance, history, isRefreshing, refundFlow) { bal, hist, refreshing, rf ->
            buildState(bal, hist, refreshing, rf)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue = buildState(balance.value, history.value, isRefreshing.value, refundFlow.value),
        )

    init {
        refresh()
    }

    private fun refresh() {
        if (isRefreshing.value) return
        isRefreshing.update { true }
        viewModelScope.launch {
            try {
                refreshBalance()
                val historyResult = getHistory()
                history.update {
                    if (historyResult == null) {
                        HistoryResult.Error
                    } else {
                        HistoryResult.Loaded(historyResult)
                    }
                }
            } finally {
                isRefreshing.update { false }
            }
        }
    }

    private suspend fun refreshBalance() {
        val result =
            runCatching {
                val address = accountProvider.resolve().address
                BalanceLoad.Loaded(address = address, balance = rpc.getUsdcBalance(network.usdcAddress, address))
            }.onFailure { Twig.warn(it) { "P2pTransactionsVM: balance fetch failed" } }
                .getOrNull()
        balance.update { result ?: BalanceLoad.Unavailable }
    }

    private fun onRefundClick() {
        if (refundFlow.value is RefundFlow.InProgress) return
        refundFlow.update { RefundFlow.Confirming }
    }

    private fun onDismissConfirm() {
        if (refundFlow.value !is RefundFlow.Confirming) return
        refundFlow.update { RefundFlow.Idle }
    }

    private fun onConfirmRefund() {
        if (refundFlow.value is RefundFlow.InProgress) return
        refundFlow.update { RefundFlow.InProgress }
        viewModelScope.launch {
            // orderId = null: skip the per-order cleanup path; the orchestrator just transfers the
            // smart-account USDC balance to the NEAR pullback target (mainnet) or noops (testnet).
            driver.bridgeFundsBackToZec(orderId = null).collect { status ->
                when (status) {
                    is OfframpStatus.FundsRecovered -> {
                        refundFlow.update { RefundFlow.Idle }
                        refreshBalance()
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
        bal: BalanceLoad,
        hist: HistoryResult,
        refreshing: Boolean,
        rf: RefundFlow,
    ): P2pTransactionsState {
        val balanceView = balanceUi(bal)
        val rows = (hist as? HistoryResult.Loaded)?.items?.map { it.toRow(network) }.orEmpty()
        val refundUi = refundUi(bal, rf)
        val confirmDialog =
            (rf as? RefundFlow.Confirming)?.let {
                ConfirmRefundDialog(
                    amount = formatBalanceAmount(bal),
                    onConfirm = ::onConfirmRefund,
                    onDismiss = ::onDismissConfirm,
                )
            }
        return P2pTransactionsState(
            onBack = ::onBack,
            onRefresh = ::refresh,
            isRefreshing = refreshing,
            balance = balanceView,
            refund = refundUi,
            confirmRefund = confirmDialog,
            rows = rows,
            emptyMessage =
                if (hist is HistoryResult.Loaded && rows.isEmpty()) {
                    stringRes(R.string.p2p_transactions_empty)
                } else {
                    null
                },
            errorMessage =
                if (hist is HistoryResult.Error) {
                    stringRes(R.string.p2p_transactions_error)
                } else {
                    null
                },
        )
    }

    private fun balanceUi(bal: BalanceLoad): BalanceState =
        when (bal) {
            BalanceLoad.Loading -> {
                BalanceState.Loading
            }

            BalanceLoad.Unavailable -> {
                BalanceState.Unavailable
            }

            is BalanceLoad.Loaded -> {
                BalanceState.Loaded(
                    balanceUsdc =
                        stringRes(
                            R.string.p2p_transactions_balance_amount,
                            bal.balance.toDisplayString(stripTrailingZeros = true)
                        ),
                    accountAddressShort =
                        bal.address.checksumHex.ellipsizeMiddle(
                            prefix = ADDRESS_ELLIPSIS_PREFIX,
                            suffix = ADDRESS_ELLIPSIS_SUFFIX
                        ),
                    accountExplorerUrl = network.addressUrl(bal.address.checksumHex),
                )
            }
        }

    private fun refundUi(bal: BalanceLoad, rf: RefundFlow): RefundUiState {
        // Refund route exists only on mainnet (testnet has no NEAR target).
        if (network.chainId != ChainId.BASE_MAINNET) return RefundUiState.Hidden
        val loaded = bal as? BalanceLoad.Loaded ?: return RefundUiState.Hidden
        if (loaded.balance <= Usdc6.ZERO) return RefundUiState.Hidden
        return when (rf) {
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
    }

    private fun formatBalanceAmount(bal: BalanceLoad) =
        stringRes(
            R.string.p2p_transactions_balance_amount,
            ((bal as? BalanceLoad.Loaded)?.balance ?: Usdc6.ZERO).toDisplayString(stripTrailingZeros = true),
        )

    private fun onBack() = navigationRouter.back()

    private sealed interface HistoryResult {
        data object Loading : HistoryResult

        data object Error : HistoryResult

        data class Loaded(
            val items: List<P2pOrderHistoryItem>
        ) : HistoryResult
    }

    private sealed interface BalanceLoad {
        data object Loading : BalanceLoad

        data object Unavailable : BalanceLoad

        data class Loaded(
            val address: Address,
            val balance: Usdc6
        ) : BalanceLoad
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
    }
}
