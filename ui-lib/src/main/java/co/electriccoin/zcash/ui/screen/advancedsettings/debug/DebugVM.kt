package co.electriccoin.zcash.ui.screen.advancedsettings.debug

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import co.electriccoin.zcash.ui.common.provider.DebugForceBackgroundExecutionUnavailable
import co.electriccoin.zcash.ui.common.provider.MigrationNotifier
import co.electriccoin.zcash.ui.common.provider.PendingMigrationTorFailureStorageProvider
import co.electriccoin.zcash.ui.common.repository.EphemeralAddressRepository
import co.electriccoin.zcash.ui.common.repository.MigrationPlanRepository
import co.electriccoin.zcash.ui.common.usecase.CheckMigrationRecoveryUseCase
import co.electriccoin.zcash.ui.common.usecase.CopyToClipboardUseCase
import co.electriccoin.zcash.ui.common.usecase.GetOrchardMigrationSdkUseCase
import co.electriccoin.zcash.ui.design.component.listitem.ListItemState
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.advancedsettings.debug.db.DebugDBArgs
import co.electriccoin.zcash.ui.screen.advancedsettings.debug.orchardbalance.DebugOrchardBalanceArgs
import co.electriccoin.zcash.ui.screen.advancedsettings.debug.text.DebugTextArgs
import co.electriccoin.zcash.ui.screen.hotfix.ephemeral.EphemeralHotfixArgs
import co.electriccoin.zcash.work.MigrationScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes

class DebugVM(
    private val copyToClipboardUseCase: CopyToClipboardUseCase,
    private val ephemeralAddressRepository: EphemeralAddressRepository,
    private val accountDataSource: AccountDataSource,
    private val getOrchardMigrationSdk: GetOrchardMigrationSdkUseCase,
    private val migrationPlanRepository: MigrationPlanRepository,
    private val pendingMigrationTorFailureStorageProvider: PendingMigrationTorFailureStorageProvider,
    private val migrationNotifier: MigrationNotifier,
    private val checkMigrationRecovery: CheckMigrationRecoveryUseCase,
    private val context: Context,
    private val navigationRouter: NavigationRouter,
) : ViewModel() {
    val state: StateFlow<DebugState> =
        MutableStateFlow(
            DebugState(
                onBack = ::onBack,
                items =
                    listOf(
                        ListItemState(
                            // bigIcon = imageRes(R.drawable.ic_zec_round_full),
                            // smallIcon = imageRes(co.electriccoin.zcash.ui.design.R.drawable.ic_zec_unshielded),
                            title = stringRes("Get Current Ephemeral Address"),
                            onClick = ::onGetEphemeralAddressClick
                        ),
                        ListItemState(
                            // bigIcon = imageRes(R.drawable.ic_zec_round_full),
                            // smallIcon = imageRes(co.electriccoin.zcash.ui.design.R.drawable.ic_zec_unshielded),
                            title = stringRes("Generate an Ephemeral Address"),
                            onClick = ::onGenerateEphemeralAddressClick
                        ),
                        ListItemState(
                            // bigIcon = imageRes(R.drawable.ic_zec_round_full),
                            // smallIcon = imageRes(co.electriccoin.zcash.ui.design.R.drawable.ic_zec_unshielded),
                            title = stringRes("Discover Funds"),
                            onClick = ::onDiscoverFundsClick
                        ),
                        ListItemState(
                            // bigIcon = imageRes(R.drawable.ic_zec_round_full),
                            // smallIcon = imageRes(co.electriccoin.zcash.ui.design.R.drawable.ic_zec_unshielded),
                            title = stringRes("Query Database"),
                            onClick = ::onQueryDatabaseClick
                        ),
                        ListItemState(
                            title = stringRes("Current Shield Addresses"),
                            onClick = ::onCurrentShieldAddressesClick
                        ),
                        ListItemState(
                            title = stringRes("Set Mock Orchard Balance (Migration)"),
                            onClick = ::onSetMockOrchardBalanceClick
                        ),
                        ListItemState(
                            title = stringRes("Migration restart"),
                            onClick = ::onMigrationRestartClick
                        ),
                        ListItemState(
                            title = stringRes("Migration reschedule transfers (fast test)"),
                            onClick = ::onMigrationRescheduleTransfersClick
                        ),
                        ListItemState(
                            title = stringRes("Migration: simulate Tor background failure"),
                            onClick = ::onSimulateMigrationTorFailureClick
                        ),
                        ListItemState(
                            title = stringRes("Migration: toggle 'no background execution' (Transfer Ready to Send)"),
                            onClick = ::onToggleBackgroundExecutionUnavailableClick
                        )
                    )
            )
        ).asStateFlow()

    private fun onBack() = navigationRouter.back()

    private fun onGetEphemeralAddressClick() =
        viewModelScope.launch {
            val address = ephemeralAddressRepository.get()
            copyToClipboardUseCase(address?.address.toString())
            navigationRouter.forward(
                DebugTextArgs(
                    title = "Current Ephemeral Address",
                    text = address.toString()
                )
            )
        }

    private fun onGenerateEphemeralAddressClick() =
        viewModelScope.launch {
            val address = ephemeralAddressRepository.create()
            copyToClipboardUseCase(address.address)
            navigationRouter.forward(
                DebugTextArgs(
                    title = "New Ephemeral Address",
                    text = address.toString()
                )
            )
        }

    private fun onCurrentShieldAddressesClick() =
        viewModelScope.launch {
            val accounts = accountDataSource.getAllAccounts()
            val text =
                accounts.joinToString("\n\n") { account ->
                    val label =
                        when (account) {
                            is ZashiAccount -> "Zashi"
                            is KeystoneAccount -> "Keystone"
                        }
                    "$label\n${account.unified.address.address}"
                }
            copyToClipboardUseCase(text)
            navigationRouter.forward(
                DebugTextArgs(
                    title = "Current Shield Addresses",
                    text = text
                )
            )
        }

    private fun onDiscoverFundsClick() = navigationRouter.forward(EphemeralHotfixArgs(null))

    private fun onQueryDatabaseClick() = navigationRouter.forward(DebugDBArgs)

    private fun onSetMockOrchardBalanceClick() = navigationRouter.forward(DebugOrchardBalanceArgs)

    // Wipes the current account's in-progress migration entirely (see OrchardMigrationSdk.
    // clearMigration's kdoc) so a fresh propose/commit can be tested immediately, instead of
    // waiting out or resuming whatever migration is already in progress.
    private fun onMigrationRestartClick() =
        viewModelScope.launch {
            getOrchardMigrationSdk()?.clearMigration()
            // Without this, GetHomeMessageUseCase.migrationMessageFor's `plan == null` fallback
            // never fires: the stale app-side plan blocks the home banner even though the SDK's
            // migration state is back to NotStarted, so no "start a new migration" message shows.
            migrationPlanRepository.clear()
            navigationRouter.forward(
                DebugTextArgs(
                    title = "Migration restart",
                    text = "Migration cleared. Propose a new migration to test."
                )
            )
        }

    // Reschedules an already-committed migration's transfers to become due within minutes instead
    // of ZIP 318's normal ~3h-apart privacy schedule (see OrchardMigrationSdk.
    // debugRescheduleTransfers's kdoc) — run this AFTER confirming a migration, not instead of it.
    //
    // Rewriting the Rust-persisted scheduled_height alone isn't enough: MigrationScheduler's
    // WorkManager job was already enqueued with a fixed initial delay computed from the ORIGINAL
    // (long) schedule at confirm time, and won't fire any sooner on its own. Re-arming it here
    // with a short delay (via ExistingWorkPolicy.REPLACE) is what actually makes the background
    // worker check again soon enough to broadcast the now-due transfer.
    private fun onMigrationRescheduleTransfersClick() =
        viewModelScope.launch {
            val count = getOrchardMigrationSdk()?.debugRescheduleTransfers() ?: 0
            MigrationScheduler(context).schedule(3.minutes)
            navigationRouter.forward(
                DebugTextArgs(
                    title = "Migration reschedule transfers",
                    text = if (count > 0) {
                        "$count transfer(s) rescheduled: first due in ~2.5 min, then ~5 min apart. " +
                            "Background worker re-armed to check again in ~3 min."
                    } else {
                        "0 transfers rescheduled — no in-progress migration found, or every " +
                            "transfer is already broadcast/mined. Nothing was changed."
                    }
                )
            )
        }

    // Reproduces spec §6.2's "background Tor failure" state (MigrationWorker's non-retryable
    // NetworkError-while-useTor branch) without waiting for a real background run to fail — sets
    // the same persisted flag and posts the same notification, then immediately re-runs the same
    // on-launch reconciliation HomeVM's init{} triggers, so the Sending screen shows up right away
    // instead of only on the next app relaunch/foreground.
    private fun onSimulateMigrationTorFailureClick() =
        viewModelScope.launch {
            pendingMigrationTorFailureStorageProvider.store(true)
            migrationNotifier.notifyMigrationTorFailure()
            checkMigrationRecovery()
            navigationRouter.forward(
                DebugTextArgs(
                    title = "Migration: simulate Tor background failure",
                    text = "Pending Tor failure flag set. Routing to the Sending screen now " +
                        "(same routing HomeVM triggers on every launch/foreground)."
                )
            )
        }

    // Spec §6.4 "Transfer Ready to Send" is otherwise only reachable by actually revoking the
    // app's battery-optimization exemption from system Settings — this flips a debug-only override
    // read by IsBackgroundExecutionAvailableProvider.isAvailable() instead, so QA can toggle the
    // condition on demand. Toggling back "on" (available) doesn't undo an already-shown banner —
    // that still needs a fresh reconciliation pass (e.g. reopening the app) to re-evaluate.
    private fun onToggleBackgroundExecutionUnavailableClick() =
        viewModelScope.launch {
            val nowForced = !DebugForceBackgroundExecutionUnavailable.isForced(context)
            DebugForceBackgroundExecutionUnavailable.set(context, nowForced)
            checkMigrationRecovery()
            navigationRouter.forward(
                DebugTextArgs(
                    title = "Migration: toggle 'no background execution'",
                    text = if (nowForced) {
                        "Background execution now forced UNAVAILABLE. Reschedule a transfer to be " +
                            "due soon (see 'Migration reschedule transfers') to see the Transfer " +
                            "Ready to Send banner/screen."
                    } else {
                        "Background execution restored to the device's real state."
                    }
                )
            )
        }
}
