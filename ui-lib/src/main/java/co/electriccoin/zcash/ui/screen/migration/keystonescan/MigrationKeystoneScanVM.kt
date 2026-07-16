package co.electriccoin.zcash.ui.screen.migration.keystonescan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.datasource.ZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.model.migration.MigrationTransferFailureState
import co.electriccoin.zcash.ui.common.model.migration.migrationFailureMessage
import co.electriccoin.zcash.ui.common.repository.PendingMigrationScheduleRepository
import co.electriccoin.zcash.ui.common.usecase.FinalizeMigrationScheduleUseCase
import co.electriccoin.zcash.ui.common.usecase.GetOrchardMigrationSdkUseCase
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.scan.ScanValidationState
import co.electriccoin.zcash.ui.screen.scankeystone.model.ScanKeystoneState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MigrationKeystoneScanVM(
    private val args: MigrationKeystoneScanArgs,
    private val getOrchardMigrationSdk: GetOrchardMigrationSdkUseCase,
    private val pendingSchedule: PendingMigrationScheduleRepository,
    private val finalizeMigrationSchedule: FinalizeMigrationScheduleUseCase,
    private val navigationRouter: NavigationRouter,
    private val zashiSpendingKeyDataSource: ZashiSpendingKeyDataSource,
) : ViewModel() {

    val validationState = MutableStateFlow(ScanValidationState.NONE)

    val state = MutableStateFlow(
        ScanKeystoneState(
            progress = null,
            message = stringRes("Scan the QR code shown on your Keystone device after signing."),
        )
    )

    val failureSheet = MutableStateFlow<MigrationTransferFailureState?>(null)

    private var isProcessing = false

    // Any scanned content is accepted as "success" here — no real Keystone-migration signature UR
    // format exists yet to validate against (see MigrationKeystoneSignVM's MOCK_QR_DATA note).
    fun onScanned(result: String) {
        if (isProcessing) return
        isProcessing = true
        viewModelScope.launch {
            val sched = pendingSchedule.get()
            if (sched == null) {
                // Edge case only (e.g. process death mid-flow) — bounce back to Confirm Transfer
                // Plan, which will propose a fresh schedule.
                navigationRouter.back()
                return@launch
            }
            // TODO: this is a mock-era stand-in — Keystone accounts have no software spending
            // key at all, so this derives the wrong (Zashi) account's key. Replace with the real
            // external-signer path (create_unsigned_transfer_pczts/store_signed_schedule_pczts)
            // once that's wired; see MigrationSdk.kt's Keystone-related implementation notes.
            val sdk = getOrchardMigrationSdk() ?: error("MigrationKeystoneScanVM: no wallet available to sign")
            sdk.signAndStoreMigrationSchedule(sched, zashiSpendingKeyDataSource.getZashiSpendingKey())
            val failure = finalizeMigrationSchedule(sched, args.mode, args.useTor, args.backgroundAvailable)
            isProcessing = false
            if (failure != null) {
                failureSheet.update {
                    MigrationTransferFailureState(
                        message = migrationFailureMessage(failure),
                        onRetry = { failureSheet.value = null; onScanned(result) },
                        onDismiss = { failureSheet.value = null },
                    )
                }
            } else {
                pendingSchedule.clear()
            }
        }
    }

    fun onBack() = navigationRouter.back()
}
