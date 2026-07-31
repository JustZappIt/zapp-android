package co.electriccoin.zcash.ui.screen.migration.keystonescan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.NetworkPrivacyOptions
import cash.z.ecc.android.sdk.TransferResult
import co.electriccoin.zcash.migration.migrationLog
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.model.KeystoneFirmwarePolicy
import co.electriccoin.zcash.ui.common.model.KeystoneFirmwareVersion
import co.electriccoin.zcash.ui.common.model.migration.MigrationTransferFailureState
import co.electriccoin.zcash.ui.common.model.migration.migrationFailureMessage
import co.electriccoin.zcash.ui.common.model.mutableLce
import co.electriccoin.zcash.ui.common.model.stateIn
import co.electriccoin.zcash.ui.common.model.toKeystoneFwVersion
import co.electriccoin.zcash.ui.common.model.toStorageKeyId
import co.electriccoin.zcash.ui.common.provider.IsMigrationTorEnabledStorageProvider
import co.electriccoin.zcash.ui.common.repository.PendingKeystoneMigrationPcztsRepository
import co.electriccoin.zcash.ui.common.repository.PendingMigrationScheduleRepository
import co.electriccoin.zcash.ui.common.usecase.FinalizeMigrationScheduleUseCase
import co.electriccoin.zcash.ui.common.usecase.GetOrchardMigrationSdkUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.migration.keystonesign.MigrationKeystoneSignArgs
import co.electriccoin.zcash.ui.screen.migration.keystonesign.keystoneBatchRoundSlice
import co.electriccoin.zcash.ui.screen.migration.keystonesign.keystoneBatchTotalRounds
import co.electriccoin.zcash.ui.screen.scan.ScanValidationState
import co.electriccoin.zcash.ui.screen.scankeystone.model.ScanKeystoneState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MigrationKeystoneScanVM(
    private val args: MigrationKeystoneScanArgs,
    private val getOrchardMigrationSdk: GetOrchardMigrationSdkUseCase,
    private val getSelectedWalletAccount: GetSelectedWalletAccountUseCase,
    private val pendingSchedule: PendingMigrationScheduleRepository,
    private val pendingKeystonePczts: PendingKeystoneMigrationPcztsRepository,
    private val finalizeMigrationSchedule: FinalizeMigrationScheduleUseCase,
    private val isMigrationTorEnabledStorageProvider: IsMigrationTorEnabledStorageProvider,
    private val navigationRouter: NavigationRouter,
) : ViewModel() {
    val validationState = MutableStateFlow(ScanValidationState.NONE)

    val state =
        MutableStateFlow(
            ScanKeystoneState(
                progress = null,
                message = stringRes("Scan the QR code shown on your Keystone device after signing."),
            )
        )

    val failureSheet = MutableStateFlow<MigrationTransferFailureState?>(null)

    // Covers only the LAST round's finish (apply signatures, submit the split, store the
    // schedule, finalize) — the real network/JNI work the QR camera gives no feedback on
    // otherwise. Deliberately NOT wrapped around per-frame decodeKeystoneSignBatchPart() calls
    // below (that would flicker loading on every scanned QR chunk) or the fast, local
    // multi-round carry-forward (no network I/O, matches prior instant-navigate behavior).
    private val finalizingLce = mutableLce<Unit>()
    val isFinalizing: StateFlow<Boolean> = finalizingLce.loading.stateIn(this, initialValue = false)

    private var isProcessing = false
    private var hasResetDecoder = false

    // "cypherpunk" 3.0.2 is the first Keystone firmware that supports migration batch signing at
    // all — older firmware either can't sign the batch correctly or won't report a version, and
    // both cases must block broadcast, not silently proceed.
    private val requiredFirmware = KeystoneFirmwareVersion(displayMajor = 3, minor = 0, build = 2)

    fun onScanned(result: String) {
        if (isProcessing || finalizingLce.state.value.loading) return
        isProcessing = true
        viewModelScope.launch {
            val accountKeyId = getSelectedWalletAccount().sdkAccount.accountUuid.toStorageKeyId()
            val sched = pendingSchedule.get(accountKeyId)
            val pending = pendingKeystonePczts.get(accountKeyId)
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
            val decoded =
                runCatching { sdk.decodeKeystoneSignBatchPart(result, pending.requestId) }
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

            // Firmware can't change mid-batch (same physical device every round), so checking on
            // round 0 only is sufficient and avoids making the user scan through every remaining
            // round only to be blocked at the very end.
            if (pending.roundIndex == 0) {
                val detected = decoded.firmwareVersion?.toKeystoneFwVersion()
                val outcome = KeystoneFirmwarePolicy.evaluate(detected, requiredFirmware)
                migrationLog(
                    "MigrationKeystoneScanVM: detected Keystone firmware " +
                        "${detected ?: "none"} (required $requiredFirmware) -> $outcome"
                )
                if (outcome != KeystoneFirmwarePolicy.Outcome.OK) {
                    isProcessing = false
                    failureSheet.update {
                        MigrationTransferFailureState(
                            message =
                                "Your Keystone firmware doesn't support migration yet. " +
                                    "Update your Keystone device, then come back to retry.",
                            // Nothing to retry without a physical firmware update — both actions
                            // just dismiss and back out, unlike the network-failure sheet below.
                            onRetry = {
                                failureSheet.value = null
                                navigationRouter.back()
                            },
                            onDismiss = {
                                failureSheet.value = null
                                navigationRouter.back()
                            },
                        )
                    }
                    return@launch
                }
            }

            // This round's slice only — the scanned response covers exactly what buildBatch()
            // built for pending.roundIndex, not the whole (possibly multi-round) batch.
            val roundBudget = sdk.keystoneSigningRoundBudget()
            val slice =
                keystoneBatchRoundSlice(
                    roundIndex = pending.roundIndex,
                    hasSplit = pending.splitUnsignedPczt != null,
                    prepCount = pending.prepUnsignedPczts.size,
                    transferCount = pending.transferUnsignedPczts.size,
                    budget = roundBudget,
                )
            val prepsForRound = pending.prepUnsignedPczts.slice(slice.prepRange)
            val transfersForRound = pending.transferUnsignedPczts.slice(slice.transferRange)
            val splitForRound = if (slice.includeSplit) pending.splitUnsignedPczt else null

            val signed =
                sdk.applyKeystoneBatchSignatures(
                    splitUnsignedPczt = splitForRound,
                    // Same [preps..., transfers...] order the sign screen built the QR with — the
                    // response list aligns positionally, split back by the same counts below.
                    transferUnsignedPczts = (prepsForRound + transfersForRound).map { it.second },
                    batchSignResponse = data,
                )
            migrationLog(
                "KeystoneScan: round ${pending.roundIndex} signatures applied " +
                    "(split=${signed.splitSignedPczt != null}, preps=${prepsForRound.size}, " +
                    "transfers=${transfersForRound.size})"
            )

            val accumulatedSplitSigned = signed.splitSignedPczt ?: pending.accumulatedSplitSigned
            val signedPreps = signed.transferSignedPczts.take(prepsForRound.size)
            val signedTransfers = signed.transferSignedPczts.drop(prepsForRound.size)
            val accumulatedPrepSigned =
                pending.accumulatedPrepSigned +
                    prepsForRound.map { it.first }.zip(signedPreps)
            val accumulatedTransferSigned =
                pending.accumulatedTransferSigned +
                    transfersForRound.map { it.first }.zip(signedTransfers)

            val totalRounds =
                keystoneBatchTotalRounds(
                    hasSplit = pending.splitUnsignedPczt != null,
                    prepCount = pending.prepUnsignedPczts.size,
                    transferCount = pending.transferUnsignedPczts.size,
                    budget = roundBudget,
                )
            if (pending.roundIndex + 1 < totalRounds) {
                // More rounds remain — carry the accumulated signatures forward and hand off to a
                // fresh sign-screen instance for the next round. replace() keeps the back stack at
                // a constant depth regardless of how many rounds a large migration needs.
                pendingKeystonePczts.set(
                    accountKeyId,
                    pending.copy(
                        roundIndex = pending.roundIndex + 1,
                        accumulatedSplitSigned = accumulatedSplitSigned,
                        accumulatedPrepSigned = accumulatedPrepSigned,
                        accumulatedTransferSigned = accumulatedTransferSigned,
                    )
                )
                isProcessing = false
                migrationLog(
                    "KeystoneScan: round ${pending.roundIndex} done — handing off to round ${pending.roundIndex + 1} of $totalRounds"
                )
                navigationRouter.replace(MigrationKeystoneSignArgs(args.mode))
                return@launch
            }

            // Last (or only) round — finish using the FULL accumulated signed set, not just this
            // round's slice. This is the real network/JNI work (Tor submit, schedule storage,
            // finalize) with no other feedback on the QR screen, so it's tracked via
            // finalizingLce/isFinalizing for the loading overlay.
            isProcessing = false
            finalizingLce.execute {
                val splitSignedPczt = accumulatedSplitSigned
                if (splitSignedPczt != null) {
                    val useTor = isMigrationTorEnabledStorageProvider.get()
                    val splitResult =
                        sdk.storeSignedNoteSplitPczt(
                            splitSignedPczt,
                            NetworkPrivacyOptions(useTor = useTor),
                        )
                    if (splitResult !is TransferResult.Success) {
                        failureSheet.update {
                            MigrationTransferFailureState(
                                message = migrationFailureMessage(splitResult),
                                onRetry = {
                                    failureSheet.value = null
                                    onScanned(result)
                                },
                                onDismiss = { failureSheet.value = null },
                            )
                        }
                        return@execute
                    }
                }
                // Kind-agnostic per-id signature application — extra PREPARATIONS of the
                // note-split tree go through the same call as the transfers.
                sdk.storeSignedSchedulePczts(accumulatedPrepSigned + accumulatedTransferSigned)
                migrationLog(
                    "KeystoneScan: stored ${accumulatedPrepSigned.size} signed prep + " +
                        "${accumulatedTransferSigned.size} signed transfer PCZT(s) " +
                        "(split=${splitSignedPczt != null}) — finalizing the schedule"
                )
                finalizeMigrationSchedule(sched, args.mode)
                pendingSchedule.clear()
                pendingKeystonePczts.clear()
            }
        }
    }

    fun onBack() = navigationRouter.back()
}
