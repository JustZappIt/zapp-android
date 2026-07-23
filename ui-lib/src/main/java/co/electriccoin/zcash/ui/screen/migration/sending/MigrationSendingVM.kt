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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

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
    private val failure = MutableStateFlow<TransferResult?>(null)

    init {
        pendingMigrationTorFailureDecisionRepository.decision
            .filterNotNull()
            .onEach { useTor ->
                pendingMigrationTorFailureDecisionRepository.clear()
                sendLce.execute { sendOnce(useTor) }
            }.launchIn(viewModelScope)
    }

    val state: StateFlow<LceState<MigrationSendingState>> =
        combine(sendLce.state, failure) { _, f ->
            MigrationSendingState(
                failureSheet = f?.let {
                    MigrationTransferFailureState(
                        message = migrationFailureMessage(it),
                        onRetry = { failure.value = null; send() },
                        onDismiss = { failure.value = null; navigationRouter.back() },
                    )
                }
            )
        }.withLce(sendLce, errorStateMapper::mapToState)
            .stateIn(this)

    fun send() = sendLce.execute { sendOnce(useTor = isTorEnabledStorageProvider.get() == true) }

    private suspend fun sendOnce(useTor: Boolean) {
        when (
            val result =
                getOrchardMigrationSdk()?.executeNextPendingTransfer(NetworkPrivacyOptions(useTor = useTor))
        ) {
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
                    navigationRouter.forward(MigrationSuccessArgs(result.txId))
                }
            }
            // A NetworkError while Tor was in use is presumptively a Tor-connectivity failure —
            // routed to its own sheet (offering "continue without Tor") instead of the generic
            // "Couldn't Send" one, since the fix (drop Tor) differs from a real network outage.
            is TransferResult.NetworkError -> {
                if (useTor) {
                    navigationRouter.forward(MigrationTorFailureArgs)
                } else {
                    failure.value = result
                }
            }
            null -> Unit
            else -> failure.value = result
        }
    }
}
