package co.electriccoin.zcash.migration

import android.content.Context
import android.content.Intent
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import co.electriccoin.zcash.migration.BuildConfig
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.migration.MigrationAppHooks
import co.electriccoin.zcash.ui.common.migration.MigrationDebugActions
import co.electriccoin.zcash.ui.common.migration.MigrationGate
import co.electriccoin.zcash.ui.common.migration.MigrationNavContributor
import co.electriccoin.zcash.ui.common.migration.MigrationNavigator
import co.electriccoin.zcash.ui.common.migration.MigrationSyncedHook
import co.electriccoin.zcash.ui.common.model.toStorageKeyId
import co.electriccoin.zcash.ui.common.provider.MigrationNotifier
import co.electriccoin.zcash.ui.common.provider.MigrationShiftCounterStorageProvider
import co.electriccoin.zcash.ui.common.provider.PendingMigrationTorFailureStorageProvider
import co.electriccoin.zcash.ui.common.repository.MigrationPlanRepository
import co.electriccoin.zcash.ui.common.repository.RestartMigrationScheduleRepository
import co.electriccoin.zcash.ui.common.usecase.CheckMigrationRecoveryUseCase
import co.electriccoin.zcash.ui.common.usecase.DebugStartMigrationE2EUseCase
import co.electriccoin.zcash.ui.common.usecase.GetOrchardMigrationSdkUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.common.usecase.OnMigrationSyncCompletedUseCase
import co.electriccoin.zcash.ui.dialogComposable
import co.electriccoin.zcash.ui.screen.home.HomeArgs
import co.electriccoin.zcash.ui.screen.migration.battery.MigrationBatteryArgs
import co.electriccoin.zcash.ui.screen.migration.battery.MigrationBatteryScreen
import co.electriccoin.zcash.ui.screen.migration.complete.MigrationCompleteArgs
import co.electriccoin.zcash.ui.screen.migration.complete.MigrationCompleteScreen
import co.electriccoin.zcash.ui.screen.migration.customservertor.MigrationCustomServerTorArgs
import co.electriccoin.zcash.ui.screen.migration.customservertor.MigrationCustomServerTorScreen
import co.electriccoin.zcash.ui.screen.migration.howitworks.MigrationHowItWorksArgs
import co.electriccoin.zcash.ui.screen.migration.howitworks.MigrationHowItWorksScreen
import co.electriccoin.zcash.ui.screen.migration.invalid.MigrationTransferInvalidArgs
import co.electriccoin.zcash.ui.screen.migration.invalid.MigrationTransferInvalidScreen
import co.electriccoin.zcash.ui.screen.migration.keystonescan.MigrationKeystoneScanArgs
import co.electriccoin.zcash.ui.screen.migration.keystonescan.MigrationKeystoneScanScreen
import co.electriccoin.zcash.ui.screen.migration.keystonesign.MigrationKeystoneSignArgs
import co.electriccoin.zcash.ui.screen.migration.keystonesign.MigrationKeystoneSignScreen
import co.electriccoin.zcash.ui.screen.migration.lockexplainer.MigrationLockExplainerArgs
import co.electriccoin.zcash.ui.screen.migration.lockexplainer.MigrationLockExplainerScreen
import co.electriccoin.zcash.ui.screen.migration.notification.MigrationNotificationArgs
import co.electriccoin.zcash.ui.screen.migration.notification.MigrationNotificationScreen
import co.electriccoin.zcash.ui.screen.migration.privacy.MigrationPrivacyArgs
import co.electriccoin.zcash.ui.screen.migration.privacy.MigrationPrivacyScreen
import co.electriccoin.zcash.ui.screen.migration.progress.MigrationProgressArgs
import co.electriccoin.zcash.ui.screen.migration.progress.MigrationProgressScreen
import co.electriccoin.zcash.ui.screen.migration.review.MigrationReviewArgs
import co.electriccoin.zcash.ui.screen.migration.review.MigrationReviewScreen
import co.electriccoin.zcash.ui.screen.migration.scheduled.MigrationScheduledArgs
import co.electriccoin.zcash.ui.screen.migration.scheduled.MigrationScheduledScreen
import co.electriccoin.zcash.ui.screen.migration.sending.MigrationSendingArgs
import co.electriccoin.zcash.ui.screen.migration.sending.MigrationSendingScreen
import co.electriccoin.zcash.ui.screen.migration.setup.MigrationSetupArgs
import co.electriccoin.zcash.ui.screen.migration.setup.MigrationSetupScreen
import co.electriccoin.zcash.ui.screen.migration.success.MigrationSuccessArgs
import co.electriccoin.zcash.ui.screen.migration.success.MigrationSuccessScreen
import co.electriccoin.zcash.ui.screen.migration.torfailure.MigrationTorFailureArgs
import co.electriccoin.zcash.ui.screen.migration.torfailure.MigrationTorFailureScreen
import co.electriccoin.zcash.ui.screen.migration.transferreview.MigrationTransferReviewArgs
import co.electriccoin.zcash.ui.screen.migration.transferreview.MigrationTransferReviewScreen
import co.electriccoin.zcash.work.MigrationScheduler
import co.electriccoin.zcash.work.MigrationSyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class MigrationGateImpl(
    private val migrationPlanRepository: MigrationPlanRepository,
) : MigrationGate {
    override suspend fun isMigrationActive(): Boolean = migrationPlanRepository.load() != null
}

class MigrationSyncedHookImpl(
    private val migrationPlanRepository: MigrationPlanRepository,
    private val onMigrationSyncCompleted: OnMigrationSyncCompletedUseCase,
    private val getSelectedWalletAccount: GetSelectedWalletAccountUseCase,
) : MigrationSyncedHook {
    override suspend fun onSynced() {
        if (migrationPlanRepository.load() == null) return
        val accountKeyId = getSelectedWalletAccount().sdkAccount.accountUuid.toStorageKeyId()
        onMigrationSyncCompleted(accountKeyId)
    }
}

class MigrationAppHooksImpl(
    private val checkMigrationRecovery: CheckMigrationRecoveryUseCase,
    private val debugStartMigrationE2E: DebugStartMigrationE2EUseCase,
    private val navigationRouter: NavigationRouter,
) : MigrationAppHooks {
    override fun handleIntent(
        intent: Intent,
        scope: CoroutineScope
    ): Boolean =
        when {
            BuildConfig.DEBUG &&
                intent.getBooleanExtra(DebugStartMigrationE2EUseCase.EXTRA_START_MIGRATION, false) -> {
                // Debug-only E2E driver: reset + commit a fresh AUTOMATIC plan from adb, no UI taps.
                scope.launch { debugStartMigrationE2E() }
                true
            }

            intent.getBooleanExtra(MigrationNotifier.EXTRA_OPEN_MIGRATION, false) -> {
                // replaceAll ensures Home is always on the back stack regardless of how the app
                // was opened.
                navigationRouter.replaceAll(HomeArgs, MigrationProgressArgs)
                true
            }

            intent.getBooleanExtra(MigrationNotifier.EXTRA_OPEN_TRANSFER_READY, false) -> {
                // Distinct destination from EXTRA_OPEN_MIGRATION above — spec §6.4 "Transfer Ready
                // to Send" is a lighter-weight review-and-send path, not the fuller
                // Reschedule/Send-now recovery screen the overdue-transfer notification routes to.
                navigationRouter.replaceAll(HomeArgs, MigrationTransferReviewArgs)
                true
            }

            else -> {
                false
            }
        }

    override suspend fun checkRecovery() = checkMigrationRecovery()
}

class MigrationNavigatorImpl(
    private val navigationRouter: NavigationRouter,
) : MigrationNavigator {
    override fun backToMigrationReview() = navigationRouter.backTo(MigrationReviewArgs::class)
}

class MigrationNavContributorImpl : MigrationNavContributor {
    override fun contribute(navGraphBuilder: NavGraphBuilder) {
        with(navGraphBuilder) {
            composable<MigrationSetupArgs> { MigrationSetupScreen() }
            composable<MigrationHowItWorksArgs> { MigrationHowItWorksScreen() }
            composable<MigrationReviewArgs> { MigrationReviewScreen(it.toRoute()) }
            composable<MigrationKeystoneSignArgs> { MigrationKeystoneSignScreen(it.toRoute()) }
            composable<MigrationKeystoneScanArgs> { MigrationKeystoneScanScreen(it.toRoute()) }
            composable<MigrationBatteryArgs> { MigrationBatteryScreen() }
            composable<MigrationNotificationArgs> { MigrationNotificationScreen() }
            dialogComposable<MigrationPrivacyArgs> { MigrationPrivacyScreen(it.toRoute()) }
            dialogComposable<MigrationCustomServerTorArgs> { MigrationCustomServerTorScreen(it.toRoute()) }
            dialogComposable<MigrationTorFailureArgs> { MigrationTorFailureScreen() }
            dialogComposable<MigrationLockExplainerArgs> { MigrationLockExplainerScreen() }
            composable<MigrationSendingArgs> { MigrationSendingScreen() }
            composable<MigrationSuccessArgs> { MigrationSuccessScreen(it.toRoute()) }
            composable<MigrationScheduledArgs> { MigrationScheduledScreen() }
            composable<MigrationCompleteArgs> { MigrationCompleteScreen() }
            composable<MigrationProgressArgs> { MigrationProgressScreen() }
            composable<MigrationTransferReviewArgs> { MigrationTransferReviewScreen() }
            composable<MigrationTransferInvalidArgs> { MigrationTransferInvalidScreen() }
        }
    }
}

class MigrationDebugActionsImpl(
    private val accountDataSource: AccountDataSource,
    private val getOrchardMigrationSdk: GetOrchardMigrationSdkUseCase,
    private val migrationPlanRepository: MigrationPlanRepository,
    private val pendingMigrationTorFailureStorageProvider: PendingMigrationTorFailureStorageProvider,
    private val migrationShiftCounterStorageProvider: MigrationShiftCounterStorageProvider,
    private val restartMigrationScheduleRepository: RestartMigrationScheduleRepository,
    private val migrationNotifier: MigrationNotifier,
    private val checkMigrationRecovery: CheckMigrationRecoveryUseCase,
    private val context: Context,
) : MigrationDebugActions {
    // Wipes the current account's in-progress migration entirely (see OrchardMigrationSdk.
    // clearMigration's kdoc) so a fresh propose/commit can be tested immediately, instead of
    // waiting out or resuming whatever migration is already in progress.
    override suspend fun restartMigration(): String {
        val accountKeyId =
            accountDataSource
                .getSelectedAccount()
                .sdkAccount.accountUuid
                .toStorageKeyId()
        getOrchardMigrationSdk()?.clearMigration()
        // Cancel both background lanes so they don't fire for a migration that no longer exists.
        MigrationScheduler(context).cancel(accountKeyId)
        MigrationSyncScheduler(context).cancel(accountKeyId)
        // Without this, migrationMessageFor's `plan == null` fallback never fires: the stale
        // app-side plan blocks the home banner even though the SDK's migration state is back to
        // NotStarted, so no "start a new migration" message shows.
        migrationPlanRepository.clear()
        // A leftover Tor-failure flag would keep routing every app launch into the Sending
        // recovery screen for a migration that no longer exists.
        pendingMigrationTorFailureStorageProvider.store(accountKeyId, false)
        // Transfer ids restart from the same values on a fresh plan, so a stale counter
        // could resume mid-count and escalate the new plan's first shift prematurely.
        migrationShiftCounterStorageProvider.reset(accountKeyId)
        // An unconsumed restart schedule (invalid-screen Continue → debug restart instead of
        // Review) would otherwise be silently used by the next Review entry in place of a
        // fresh proposal over the post-clear balance.
        restartMigrationScheduleRepository.consume(accountKeyId)
        // Dismiss whatever migration notification is still showing — its tap routes into
        // the migration that was just cleared.
        migrationNotifier.cancel(accountKeyId)
        return "Migration cleared. Propose a new migration to test."
    }

    // Reproduces spec §6.2's "background Tor failure" state (MigrationWorker's non-retryable
    // NetworkError-while-useTor branch) without waiting for a real background run to fail — sets
    // the same persisted flag and posts the same notification, then immediately re-runs the same
    // on-launch reconciliation HomeVM's init{} triggers, so the Sending screen shows up right away
    // instead of only on the next app relaunch/foreground.
    override suspend fun simulateTorFailure(): String {
        val accountKeyId =
            accountDataSource
                .getSelectedAccount()
                .sdkAccount.accountUuid
                .toStorageKeyId()
        pendingMigrationTorFailureStorageProvider.store(true)
        migrationNotifier.notifyMigrationTorFailure(accountKeyId)
        checkMigrationRecovery()
        return "Pending Tor failure flag set. Routing to the Sending screen now " +
            "(same routing HomeVM triggers on every launch/foreground)."
    }
}
