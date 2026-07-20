package co.electriccoin.zcash.ui.screen.migration.keystonescan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.NetworkPrivacyOptions
import cash.z.ecc.android.sdk.TransferResult
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.model.migration.MigrationTransferFailureState
import co.electriccoin.zcash.ui.common.model.migration.migrationFailureMessage
import co.electriccoin.zcash.ui.common.provider.IsTorEnabledStorageProvider
import co.electriccoin.zcash.ui.common.repository.PendingKeystoneMigrationPcztsRepository
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
    private val pendingKeystonePczts: PendingKeystoneMigrationPcztsRepository,
    private val finalizeMigrationSchedule: FinalizeMigrationScheduleUseCase,
    private val isTorEnabledStorageProvider: IsTorEnabledStorageProvider,
    private val navigationRouter: NavigationRouter,
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
    private var hasResetDecoder = false

    fun onScanned(result: String) {
        if (isProcessing) return
        isProcessing = true
        viewModelScope.launch {
            val sched = pendingSchedule.get()
            val pending = pendingKeystonePczts.get()
            if (sched == null || pending == null) {
                // Edge case only (e.g. process death mid-flow) — bounce back to Confirm Transfer
                // Plan, which will propose a fresh schedule.
                navigationRouter.back()
                return@launch
            }
            val sdk = getOrchardMigrationSdk() ?: error("MigrationKeystoneScanVM: no wallet available to sign")
            if (!hasResetDecoder) {
                sdk.resetKeystoneSignBatchDecoder()
                hasResetDecoder = true
            }
            val decoded = runCatching { sdk.decodeKeystoneSignBatchPart(result, pending.requestId) }
                .getOrElse {
                    isProcessing = false
                    return@launch
                }
            state.update { it.copy(progress = decoded.progress) }
            val data = decoded.data
            if (!decoded.complete || data == null) {
                isProcessing = false
                return@launch
            }
            val signed = sdk.applyKeystoneBatchSignatures(
                splitUnsignedPczt = pending.splitUnsignedPczt,
                transferUnsignedPczts = pending.transferUnsignedPczts.map { it.second },
                batchSignResponse = data,
            )
            val splitSignedPczt = signed.splitSignedPczt
            if (splitSignedPczt != null) {
                val useTor = isTorEnabledStorageProvider.get() == true
                val splitResult = sdk.storeSignedNoteSplitPczt(
                    splitSignedPczt,
                    NetworkPrivacyOptions(useTor = useTor),
                )
                if (splitResult !is TransferResult.Success) {
                    isProcessing = false
                    failureSheet.update {
                        MigrationTransferFailureState(
                            message = migrationFailureMessage(splitResult),
                            onRetry = { failureSheet.value = null; onScanned(result) },
                            onDismiss = { failureSheet.value = null },
                        )
                    }
                    return@launch
                }
            }
            val transferIds = pending.transferUnsignedPczts.map { it.first }
            sdk.storeSignedSchedulePczts(transferIds.zip(signed.transferSignedPczts))
            finalizeMigrationSchedule(sched, args.mode)
            isProcessing = false
            pendingSchedule.clear()
            pendingKeystonePczts.clear()
        }
    }

    fun onBack() = navigationRouter.back()
}
