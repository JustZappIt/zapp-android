package co.electriccoin.zcash.ui.screen.migration.keystonesign

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.repository.PendingMigrationScheduleRepository
import co.electriccoin.zcash.ui.common.usecase.GetSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.addressbook.ADDRESS_MAX_LENGTH
import co.electriccoin.zcash.ui.screen.migration.keystonescan.MigrationKeystoneScanArgs
import co.electriccoin.zcash.ui.screen.signkeystonetransaction.SignKeystoneTransactionState
import co.electriccoin.zcash.ui.screen.signkeystonetransaction.ZashiAccountInfoListItemState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class MigrationKeystoneSignVM(
    private val args: MigrationKeystoneSignArgs,
    private val getSelectedWalletAccount: GetSelectedWalletAccountUseCase,
    private val pendingSchedule: PendingMigrationScheduleRepository,
    private val navigationRouter: NavigationRouter,
) : ViewModel() {

    val state =
        getSelectedWalletAccount.observe()
            .map { account ->
                if (account == null || pendingSchedule.get() == null) {
                    // Edge case only (e.g. process death mid-flow) — the schedule is proposed
                    // fresh every time Confirm Transfer Plan is entered, so just bounce back there.
                    navigationRouter.back()
                    return@map null
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
                    generateNextQrCode = {},
                    qrData = MOCK_QR_DATA,
                    secondaryButton = null,
                    positiveButton = ButtonState(text = stringRes("Get Signature"), onClick = ::onGetSignature),
                    negativeButton = ButtonState(text = stringRes("Reject"), onClick = ::onReject),
                    onBack = ::onReject,
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
                initialValue = null,
            )

    private fun onGetSignature() = navigationRouter.forward(MigrationKeystoneScanArgs(mode = args.mode))

    private fun onReject() {
        pendingSchedule.clear()
        navigationRouter.back()
    }

    companion object {
        // No real Rust-backed byte payload exists yet for Keystone migration signing — this is a
        // placeholder QR so the display/scan round trip is exercisable end-to-end; the scan step
        // (MigrationKeystoneScanVM) accepts any scanned content until a real UR format is defined.
        private const val MOCK_QR_DATA = "zodl-migration-schedule"
    }
}
