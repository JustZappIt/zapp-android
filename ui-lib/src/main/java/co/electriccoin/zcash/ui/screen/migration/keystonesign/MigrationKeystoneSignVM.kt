package co.electriccoin.zcash.ui.screen.migration.keystonesign

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.model.migration.MigrationTransferFailureState
import co.electriccoin.zcash.ui.common.repository.PendingKeystoneMigrationPczts
import co.electriccoin.zcash.ui.common.repository.PendingKeystoneMigrationPcztsRepository
import co.electriccoin.zcash.ui.common.repository.PendingMigrationScheduleRepository
import co.electriccoin.zcash.ui.common.usecase.GetOrchardMigrationSdkUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.addressbook.ADDRESS_MAX_LENGTH
import co.electriccoin.zcash.ui.screen.migration.keystonescan.MigrationKeystoneScanArgs
import co.electriccoin.zcash.ui.screen.signkeystonetransaction.SignKeystoneTransactionState
import co.electriccoin.zcash.ui.screen.signkeystonetransaction.ZashiAccountInfoListItemState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.util.UUID

class MigrationKeystoneSignVM(
    private val args: MigrationKeystoneSignArgs,
    private val getSelectedWalletAccount: GetSelectedWalletAccountUseCase,
    private val getOrchardMigrationSdk: GetOrchardMigrationSdkUseCase,
    private val pendingSchedule: PendingMigrationScheduleRepository,
    private val pendingKeystonePczts: PendingKeystoneMigrationPcztsRepository,
    private val navigationRouter: NavigationRouter,
) : ViewModel() {

    private val qrParts = MutableStateFlow<List<String>?>(null)
    private val qrFrameIndex = MutableStateFlow(0)

    val failureSheet = MutableStateFlow<MigrationTransferFailureState?>(null)

    init {
        buildBatch()
    }

    // Builds the unsigned split (if needed) + schedule PCZTs and the animated QR covering both in
    // one Keystone batch-signing session — see MigrationSdk.kt's buildKeystoneSignBatchQrParts doc.
    // Retains the unsigned originals (via pendingKeystonePczts) so the scan screen can match the
    // device's signatures back to them once scanned.
    private fun buildBatch() {
        val sched = pendingSchedule.get() ?: return // handled by state's null-schedule branch below
        viewModelScope.launch {
            runCatching {
                val sdk = getOrchardMigrationSdk() ?: error("MigrationKeystoneSignVM: no wallet available to sign")
                val splitUnsignedPczt = if (sdk.isNoteSplitNeeded()) sdk.createUnsignedNoteSplitPczt() else null
                val transferUnsignedPczts = sdk.createUnsignedTransferPczts(sched)
                val requestId = randomRequestId()
                val parts = sdk.buildKeystoneSignBatchQrParts(
                    requestId = requestId,
                    splitUnsignedPczt = splitUnsignedPczt,
                    transferUnsignedPczts = transferUnsignedPczts.map { it.second },
                    maxFragmentLen = MAX_FRAGMENT_LEN,
                )
                pendingKeystonePczts.set(
                    PendingKeystoneMigrationPczts(
                        requestId = requestId,
                        splitUnsignedPczt = splitUnsignedPczt,
                        transferUnsignedPczts = transferUnsignedPczts,
                    )
                )
                parts
            }.onSuccess { parts ->
                qrFrameIndex.value = 0
                qrParts.value = parts
            }.onFailure {
                failureSheet.update {
                    MigrationTransferFailureState(
                        message = "Couldn't prepare the migration for signing. Try again.",
                        onRetry = { failureSheet.value = null; buildBatch() },
                        onDismiss = { failureSheet.value = null; onReject() },
                    )
                }
            }
        }
    }

    private val combinedState: Flow<SignKeystoneTransactionState?> =
        combine(getSelectedWalletAccount.observe(), qrParts, qrFrameIndex) { account, parts, frameIndex ->
            if (account == null || pendingSchedule.get() == null) {
                // Edge case only (e.g. process death mid-flow) — the schedule is proposed
                // fresh every time Confirm Transfer Plan is entered, so just bounce back there.
                navigationRouter.back()
                return@combine null
            }
            SignKeystoneTransactionState(
                barTitle = stringRes("Sign Transaction"),
                title = stringRes("Scan with your Keystone wallet"),
                subtitle = stringRes(
                    "After you have signed with Keystone, tap on the Get Signature button below."
                ),
                accountInfo = ZashiAccountInfoListItemState(
                    icon = account.icon,
                    title = account.name,
                    subtitle = stringRes("${account.unified.address.address.take(ADDRESS_MAX_LENGTH)}..."),
                ),
                badgeText = stringRes("Hardware"),
                generateNextQrCode = {
                    val size = parts?.size ?: 1
                    qrFrameIndex.value = (frameIndex + 1) % size
                },
                qrData = parts?.getOrNull(frameIndex),
                secondaryButton = null,
                positiveButton = ButtonState(text = stringRes("Get Signature"), onClick = ::onGetSignature),
                negativeButton = ButtonState(text = stringRes("Reject"), onClick = ::onReject),
                onBack = ::onReject,
            )
        }

    val state: StateFlow<SignKeystoneTransactionState?> =
        combinedState.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT.inWholeMilliseconds),
            initialValue = null,
        )

    private fun onGetSignature() = navigationRouter.forward(MigrationKeystoneScanArgs(mode = args.mode))

    private fun onReject() {
        pendingSchedule.clear()
        pendingKeystonePczts.clear()
        navigationRouter.back()
    }

    companion object {
        // Conservative default fragment length for the animated multi-part QR — matches the
        // `keystone-sdk-android` AAR's own default (unused here directly, but a reasonable
        // reference point since Keystone devices are the same physical scan target either way).
        private const val MAX_FRAGMENT_LEN = 150

        private fun randomRequestId(): ByteArray {
            val uuid = UUID.randomUUID()
            return ByteBuffer.allocate(16)
                .putLong(uuid.mostSignificantBits)
                .putLong(uuid.leastSignificantBits)
                .array()
        }
    }
}
