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
import co.electriccoin.zcash.ui.screen.migration.keystonesign.KEYSTONE_BATCH_MAX_ITEMS
import co.electriccoin.zcash.ui.screen.migration.keystonesign.MigrationKeystoneSignArgs
import co.electriccoin.zcash.ui.screen.migration.keystonesign.keystoneBatchRoundSlice
import co.electriccoin.zcash.ui.screen.migration.keystonesign.keystoneBatchTotalRounds
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

            // This round's slice only — the scanned response covers exactly what buildBatch()
            // built for pending.roundIndex, not the whole (possibly multi-round) batch.
            val slice = keystoneBatchRoundSlice(
                roundIndex = pending.roundIndex,
                hasSplit = pending.splitUnsignedPczt != null,
                transferCount = pending.transferUnsignedPczts.size,
                maxItems = KEYSTONE_BATCH_MAX_ITEMS,
            )
            val transfersForRound = pending.transferUnsignedPczts.slice(slice.transferRange)
            val splitForRound = if (slice.includeSplit) pending.splitUnsignedPczt else null

            val signed = sdk.applyKeystoneBatchSignatures(
                splitUnsignedPczt = splitForRound,
                transferUnsignedPczts = transfersForRound.map { it.second },
                batchSignResponse = data,
            )
            val accumulatedSplitSigned = signed.splitSignedPczt ?: pending.accumulatedSplitSigned
            val accumulatedTransferSigned = pending.accumulatedTransferSigned +
                transfersForRound.map { it.first }.zip(signed.transferSignedPczts)

            val totalRounds = keystoneBatchTotalRounds(
                hasSplit = pending.splitUnsignedPczt != null,
                transferCount = pending.transferUnsignedPczts.size,
                maxItems = KEYSTONE_BATCH_MAX_ITEMS,
            )
            if (pending.roundIndex + 1 < totalRounds) {
                // More rounds remain — carry the accumulated signatures forward and hand off to a
                // fresh sign-screen instance for the next round. replace() keeps the back stack at
                // a constant depth regardless of how many rounds a large migration needs.
                pendingKeystonePczts.set(
                    pending.copy(
                        roundIndex = pending.roundIndex + 1,
                        accumulatedSplitSigned = accumulatedSplitSigned,
                        accumulatedTransferSigned = accumulatedTransferSigned,
                    )
                )
                isProcessing = false
                navigationRouter.replace(MigrationKeystoneSignArgs(args.mode))
                return@launch
            }

            // Last (or only) round — finish using the FULL accumulated signed set, not just this
            // round's slice.
            val splitSignedPczt = accumulatedSplitSigned
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
            sdk.storeSignedSchedulePczts(accumulatedTransferSigned)
            finalizeMigrationSchedule(sched, args.mode)
            isProcessing = false
            pendingSchedule.clear()
            pendingKeystonePczts.clear()
        }
    }

    fun onBack() = navigationRouter.back()
}
