package co.electriccoin.zcash.ui.screen.swap.peer.progress

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.repository.PeerCashOutRepository
import co.electriccoin.zcash.ui.common.repository.PeerCashOutRun
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.swap.peer.displayName
import co.electriccoin.zcash.ui.screen.swap.peer.order.PeerOrderArgs
import co.electriccoin.zcash.ui.screen.swap.peer.userMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import xyz.justzappit.offramp.peer.PeerCashOutId
import xyz.justzappit.offramp.peer.PeerCashOutStatus
import xyz.justzappit.offramp.peer.PeerNetworkConfig
import xyz.justzappit.offramp.peer.PeerPlatform

/**
 * Watches one cash-out. It does not own it: the run lives on an application-lifetime scope, so
 * leaving this screen stops the watching and nothing else, and coming back re-attaches to the same
 * attempt rather than starting another.
 */
internal class PeerCashOutProgressVM(
    private val navigationRouter: NavigationRouter,
    private val repository: PeerCashOutRepository,
    private val network: PeerNetworkConfig,
    args: PeerCashOutProgressArgs,
) : ViewModel() {
    private val cashOutId = PeerCashOutId.ofOrNull(args.cashOutId)

    init {
        // Idempotent: a no-op while the run is already going, and the cold-start recovery path when
        // the process died with the order unfinished.
        cashOutId?.let(repository::resume)
    }

    val state: StateFlow<PeerCashOutProgressState> =
        runFlow()
            .map(::buildState)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
                initialValue = buildState(null),
            )

    private fun runFlow(): Flow<PeerCashOutRun?> = cashOutId?.let(repository::observe) ?: flowOf(null)

    private fun buildState(run: PeerCashOutRun?): PeerCashOutProgressState {
        val latest = run?.latest ?: PeerCashOutStatus.Idle
        val failure = latest as? PeerCashOutStatus.Failed
        val platform = run?.platform ?: PeerPlatform.REVOLUT
        return PeerCashOutProgressState(
            title = titleFor(latest),
            subtitle = subtitleFor(latest),
            summary =
                run?.let {
                    PeerCashOutOrderSummary(
                        amountUsdcDisplay =
                            stringRes(
                                R.string.peer_offramp_summary_amount_value,
                                it.amount.toDisplayString(stripTrailingZeros = true),
                            ),
                        platform = platform.displayName(),
                        currencies = stringRes(it.currencies.joinToString(CURRENCY_SEPARATOR) { c -> c.code }),
                    )
                },
            steps =
                buildPeerProgressSteps(
                    status = latest,
                    platform = platform,
                    bridgingObserved = run?.statuses.orEmpty().any { it is PeerCashOutStatus.BridgingFunds },
                ),
            failure = failure?.let { failureCard(it, platform) },
            primaryButton =
                run?.depositId?.let { depositId ->
                    ButtonState(
                        text = stringRes(R.string.peer_offramp_view_order),
                        onClick = { navigationRouter.forward(PeerOrderArgs(depositId.composite)) },
                    )
                },
            isOrderLive = latest is PeerCashOutStatus.OrderLive,
            onBack = navigationRouter::back,
        )
    }

    private fun failureCard(
        failure: PeerCashOutStatus.Failed,
        platform: PeerPlatform,
    ): PeerCashOutFailureCard {
        val txHash = failure.txHash?.hex
        return PeerCashOutFailureCard(
            stepLabel = stepLabel(failure.step, platform),
            reason = failure.error.userMessage(),
            txHash = txHash,
            txExplorerUrl = txHash?.let(network::txUrl),
            // The unknown-outcome codes deliberately offer nothing to press.
            retry =
                if (failure.error.allowsManualRetry) {
                    ButtonState(text = stringRes(R.string.peer_offramp_retry), onClick = ::onRetryClick)
                } else {
                    null
                },
        )
    }

    private fun titleFor(status: PeerCashOutStatus): StringResource =
        when (status) {
            is PeerCashOutStatus.Failed -> {
                stringRes(R.string.peer_offramp_progress_title_failed)
            }

            is PeerCashOutStatus.OrderLive -> {
                if (status.snapshot.liveIntents.isNotEmpty()) {
                    stringRes(R.string.peer_offramp_progress_title_buyer_paying)
                } else {
                    stringRes(R.string.peer_offramp_progress_title_live)
                }
            }

            else -> {
                stringRes(R.string.peer_offramp_progress_title_setup)
            }
        }

    private fun subtitleFor(status: PeerCashOutStatus): StringResource? =
        when (status) {
            is PeerCashOutStatus.CreatingDeposit -> stringRes(R.string.peer_offramp_progress_subtitle_creating)
            is PeerCashOutStatus.BridgingFunds -> stringRes(R.string.peer_offramp_progress_subtitle_bridging)
            is PeerCashOutStatus.OrderLive -> stringRes(R.string.peer_offramp_progress_subtitle_live)
            else -> null
        }

    /** Resolves whatever was already broadcast when there is a checkpoint; never re-sends it. */
    private fun onRetryClick() {
        cashOutId?.let(repository::resume)
    }

    private companion object {
        const val CURRENCY_SEPARATOR = ", "
    }
}
