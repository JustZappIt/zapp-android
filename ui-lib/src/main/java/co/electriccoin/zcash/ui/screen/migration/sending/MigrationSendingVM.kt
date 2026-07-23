package co.electriccoin.zcash.ui.screen.migration.sending

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.NetworkPrivacyOptions
import cash.z.ecc.android.sdk.TransferResult
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.model.LceState
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
        data object NotReady : SendFailure
    }

    private fun SendFailure.message(): String = when (this) {
        is SendFailure.Engine -> migrationFailureMessage(result)
        SendFailure.NotReady -> "This transfer isn't ready to send yet. Please try again in a moment."
    }

    init {
        pendingMigrationTorFailureDecisionRepository.decision
            .filterNotNull()
            .onEach { useTor ->
                pendingMigrationTorFailureDecisionRepository.clear()
                sendLce.execute { sendOnce(useTor) }
            }.launchIn(viewModelScope)
        // If a Tor-failure decision is already sitting here at construction time (e.g. this VM was
        // recreated while one was pending), the collector above fires on its very first emission
        // and is the more specific, more recent user decision — calling send() here too would race
        // it via sendLce.execute()'s cancel-previous-job semantics (MutableLce.execute), silently
        // cancelling one of two legitimate send attempts. Only kick off the default send when there
        // is nothing pending for the collector to react to.
        if (pendingMigrationTorFailureDecisionRepository.decision.value == null) {
            send()
        }
    }

    val state: StateFlow<LceState<MigrationSendingState>> =
        combine(sendLce.state, failure) { _, f ->
            MigrationSendingState(
                failureSheet = f?.let {
                    MigrationTransferFailureState(
                        message = it.message(),
                        onRetry = { failure.value = null; send() },
                        onDismiss = { failure.value = null; navigationRouter.back() },
                    )
                },
                // The escape hatch stays disabled while a send is actively in flight (no failure
                // shown yet) — matches TransactionProgressVM.createSendingState()'s
                // onBack = { /* do nothing */ } while sending, wired to a real back action only once
                // there's a failure sheet the user might need to escape from.
                onBack = if (f == null) {
                    {}
                } else {
                    ::onBack
                },
            )
        }.withLce(sendLce, errorStateMapper::mapToState)
            .stateIn(this)

    private fun send() = sendLce.execute { sendOnce(useTor = isTorEnabledStorageProvider.get() == true) }

    fun onBack() = navigationRouter.back()

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
                // Re-arms the next window for a resumed/manually-confirmed transfer in a
                // multi-transfer AUTOMATIC plan; no-ops once the plan is already complete.
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
