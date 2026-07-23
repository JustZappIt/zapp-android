package co.electriccoin.zcash.ui.screen.migration.sending

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.NetworkPrivacyOptions
import cash.z.ecc.android.sdk.TransferResult
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.model.LceState
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.migration.MigrationMode
import co.electriccoin.zcash.ui.common.model.migration.MigrationTransferFailureState
import co.electriccoin.zcash.ui.common.model.migration.migrationFailureMessage
import co.electriccoin.zcash.ui.common.model.mutableLce
import co.electriccoin.zcash.ui.common.model.stateIn
import co.electriccoin.zcash.ui.common.model.withLce
import co.electriccoin.zcash.ui.common.provider.IsTorEnabledStorageProvider
import co.electriccoin.zcash.ui.common.repository.MigrationPlanRepository
import co.electriccoin.zcash.ui.common.repository.PendingMigrationTorFailureDecisionRepository
import co.electriccoin.zcash.ui.common.usecase.ErrorMapperUseCase
import co.electriccoin.zcash.ui.common.usecase.GetOrchardMigrationSdkUseCase
import co.electriccoin.zcash.ui.common.usecase.ScheduleNextMigrationWindowUseCase
import co.electriccoin.zcash.ui.screen.migration.complete.MigrationCompleteArgs
import co.electriccoin.zcash.ui.screen.migration.success.MigrationSuccessArgs
import co.electriccoin.zcash.ui.screen.migration.torfailure.MigrationTorFailureArgs
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext

class MigrationSendingVM(
    private val getOrchardMigrationSdk: GetOrchardMigrationSdkUseCase,
    private val migrationPlanRepository: MigrationPlanRepository,
    private val scheduleNextMigrationWindow: ScheduleNextMigrationWindowUseCase,
    private val navigationRouter: NavigationRouter,
    private val errorStateMapper: ErrorMapperUseCase,
    private val isTorEnabledStorageProvider: IsTorEnabledStorageProvider,
    private val pendingMigrationTorFailureDecisionRepository: PendingMigrationTorFailureDecisionRepository,
) : ViewModel() {

    private val sendLce = mutableLce<Unit>()
    private val failure = MutableStateFlow<SendFailure?>(null)

    private sealed interface SendFailure {
        data class Engine(val result: TransferResult) : SendFailure
        // Populated by the IMMEDIATE-mode integration task (see
        // docs/superpowers/plans/2026-07-23-migration-immediate-integration.md) — unused until then.
        data class Submit(val result: SubmitResult) : SendFailure
        data object NotReady : SendFailure
    }

    private fun SendFailure.message(): String = when (this) {
        is SendFailure.Engine -> migrationFailureMessage(result)
        is SendFailure.Submit -> when (result) {
            is SubmitResult.GrpcFailure -> "Couldn't reach the network. Check your connection and try again."
            is SubmitResult.Failure -> "The network rejected this transaction. Please contact support."
            is SubmitResult.Error -> "Something went wrong while sending. Please contact support."
            is SubmitResult.Partial -> "Some but not all of this transaction's parts were sent. Please contact support."
            is SubmitResult.Success -> error("SendFailure.Submit constructed with a Success result")
        }
        SendFailure.NotReady -> "This transfer isn't ready to send yet. Please try again in a moment."
    }

    // Engine (TransferResult) failures and NotReady always offer a real retry (matches today's
    // existing behavior of always calling send() again). A Submit failure only retries when it's
    // SubmitResult.GrpcFailure — resubmitting the identical signed Proposal after a genuine
    // Failure/Error/Partial would either re-fail identically or, for Partial, risk re-broadcasting
    // already-sent internal transactions.
    private fun SendFailure.isRetryable(): Boolean = when (this) {
        is SendFailure.Engine -> true
        is SendFailure.Submit -> result is SubmitResult.GrpcFailure
        SendFailure.NotReady -> true
    }

    init {
        pendingMigrationTorFailureDecisionRepository.decision
            .filterNotNull()
            .onEach { useTor ->
                pendingMigrationTorFailureDecisionRepository.clear()
                sendLce.execute { sendOnce(useTor) }
            }.launchIn(viewModelScope)
        send()
    }

    val state: StateFlow<LceState<MigrationSendingState>> =
        combine(sendLce.state, failure) { _, f ->
            MigrationSendingState(
                failureSheet = f?.let {
                    MigrationTransferFailureState(
                        message = it.message(),
                        onRetry = if (it.isRetryable()) {
                            { failure.value = null; send() }
                        } else {
                            { failure.value = null; navigationRouter.back() }
                        },
                        onDismiss = { failure.value = null; navigationRouter.back() },
                    )
                }
            )
        }.withLce(sendLce, errorStateMapper::mapToState)
            .stateIn(this)

    private fun send() = sendLce.execute { sendOnce(useTor = isTorEnabledStorageProvider.get() == true) }

    private suspend fun sendOnce(useTor: Boolean) {
        val sdk = getOrchardMigrationSdk() ?: error("MigrationSendingVM: no wallet available to send")
        var result: TransferResult? = null
        var attempt = 0
        while (result == null && attempt < SEND_MAX_ATTEMPTS) {
            if (attempt > 0) delay(SEND_RETRY_DELAY_MS)
            withContext(NonCancellable) {
                sdk.finalizeReadyTransfers()
                result = sdk.executeNextPendingTransfer(NetworkPrivacyOptions(useTor = useTor))
            }
            attempt++
        }
        when (val r = result) {
            is TransferResult.Success -> {
                // No-ops for IMMEDIATE mode's single-transfer plan (no nextPending); re-arms the
                // next window for a resumed/manually-confirmed transfer in a multi-transfer plan.
                scheduleNextMigrationWindow()
                val plan = migrationPlanRepository.load()
                if (plan?.mode == MigrationMode.AUTOMATIC && plan.isComplete) {
                    // This was the plan's last transfer — one Migration Complete screen covers
                    // both this (foreground, just confirmed) and the background-completion case
                    // (CheckMigrationRecoveryUseCase, on next app open), rather than two.
                    navigationRouter.forward(MigrationCompleteArgs)
                } else {
                    navigationRouter.forward(MigrationSuccessArgs(r.txId))
                }
            }
            // A NetworkError while Tor was in use is presumptively a Tor-connectivity failure —
            // routed to its own sheet (offering "continue without Tor") instead of the generic
            // "Couldn't Send" one, since the fix (drop Tor) differs from a real network outage.
            is TransferResult.NetworkError -> {
                if (useTor) {
                    navigationRouter.forward(MigrationTorFailureArgs)
                } else {
                    failure.value = SendFailure.Engine(r)
                }
            }
            null -> failure.value = SendFailure.NotReady
            else -> failure.value = SendFailure.Engine(r)
        }
    }

    companion object {
        private const val SEND_MAX_ATTEMPTS = 3
        private const val SEND_RETRY_DELAY_MS = 1500L
    }
}
