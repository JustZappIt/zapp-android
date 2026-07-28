package co.electriccoin.zcash.ui.common.usecase

import android.content.Context
import cash.z.ecc.android.sdk.AttentionReason
import cash.z.ecc.android.sdk.MigrationState
import cash.z.ecc.android.sdk.OrchardMigrationSdk
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.model.migration.MigrationMode
import co.electriccoin.zcash.ui.common.model.migration.MigrationPlan
import co.electriccoin.zcash.ui.common.model.migration.MigrationTransfer
import co.electriccoin.zcash.ui.common.model.migration.MigrationTransferStatus
import co.electriccoin.zcash.ui.common.provider.HasSeenMigrationCompleteStorageProvider
import co.electriccoin.zcash.ui.common.provider.IsBackgroundExecutionAvailableProvider
import co.electriccoin.zcash.ui.common.provider.PendingMigrationTorFailureStorageProvider
import co.electriccoin.zcash.ui.common.repository.MigrationPlanRepository
import co.electriccoin.zcash.ui.screen.home.HomeArgs
import co.electriccoin.zcash.ui.screen.migration.complete.MigrationCompleteArgs
import co.electriccoin.zcash.ui.screen.migration.invalid.MigrationTransferInvalidArgs
import co.electriccoin.zcash.ui.screen.migration.progress.MigrationProgressArgs
import co.electriccoin.zcash.ui.screen.migration.sending.MigrationSendingArgs
import co.electriccoin.zcash.ui.screen.migration.transferreview.MigrationTransferReviewArgs
import co.electriccoin.zcash.work.MigrationSyncScheduler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class CheckMigrationRecoveryUseCaseTest {

    @kotlin.test.BeforeTest
    fun resetThrottle() {
        CheckMigrationRecoveryUseCase.resetRunThrottleForTests()
    }

    private fun planWithPendingTransfer(scheduledAtEpochSeconds: Long) =
        MigrationPlan(
            id = "p1",
            createdAtEpochSeconds = 0L,
            transfers = listOf(
                MigrationTransfer(
                    index = 2,
                    amountZatoshi = 100_000L,
                    scheduledAtEpochSeconds = scheduledAtEpochSeconds,
                    status = MigrationTransferStatus.PENDING,
                )
            ),
            mode = MigrationMode.AUTOMATIC,
        )

    private fun useCase(
        sdk: OrchardMigrationSdk?,
        navigationRouter: NavigationRouter,
        pendingMigrationTorFailure: Boolean = false,
        hasSeenMigrationComplete: Boolean = false,
        savedPlan: MigrationPlan? = mockk(relaxed = true),
        migrationPlanRepository: MigrationPlanRepository = mockk(relaxed = true) {
            coEvery { load() } returns savedPlan
        },
        isBackgroundExecutionAvailable: Boolean = true,
        orchardBalanceZatoshi: Long = 0L,
        migrationSyncScheduler: MigrationSyncScheduler = mockk(relaxed = true),
        // Default: Lane A is always active in tests so the reconciliation branch is skipped,
        // keeping existing test behaviour unchanged. Override to test reconciliation explicitly.
        isLaneAActive: suspend () -> Boolean = { true },
    ) = CheckMigrationRecoveryUseCase(
        getOrchardMigrationSdk = mockk<GetOrchardMigrationSdkUseCase> {
            coEvery { this@mockk() } returns sdk
        },
        navigationRouter = navigationRouter,
        hasSeenMigrationCompleteStorageProvider = mockk<HasSeenMigrationCompleteStorageProvider> {
            coEvery { get() } returns hasSeenMigrationComplete
        },
        migrationPlanRepository = migrationPlanRepository,
        getOrchardBalance = mockk<GetOrchardBalanceUseCase> {
            coEvery { this@mockk() } returns Zatoshi(orchardBalanceZatoshi)
        },
        pendingMigrationTorFailureStorageProvider = mockk<PendingMigrationTorFailureStorageProvider> {
            coEvery { get() } returns pendingMigrationTorFailure
        },
        isBackgroundExecutionAvailableProvider = mockk<IsBackgroundExecutionAvailableProvider> {
            every { isAvailable() } returns isBackgroundExecutionAvailable
        },
        getSelectedWalletAccount = mockk<GetSelectedWalletAccountUseCase>(relaxed = true),
        migrationSyncScheduler = migrationSyncScheduler,
        context = mockk<Context>(relaxed = true),
        isLaneAActive = isLaneAActive,
    )

    @Test
    fun pendingTorFailurePreemptsInvalidTransfersAndRoutesToSending() = runTest {
        val sdk = mockk<OrchardMigrationSdk>(relaxed = true) {
            coEvery { hasInvalidTransfers() } returns true
            coEvery { hasOverdueTransfers() } returns true
        }
        val router = mockk<NavigationRouter>(relaxed = true)

        useCase(sdk = sdk, navigationRouter = router, pendingMigrationTorFailure = true).invoke()

        coVerify(exactly = 1) { router.replaceAll(HomeArgs, MigrationSendingArgs) }
        coVerify(exactly = 0) { router.replaceAll(HomeArgs, MigrationTransferInvalidArgs) }
    }

    @Test
    fun noPendingTorFailureFallsThroughToInvalidTransferCheck() = runTest {
        val sdk = mockk<OrchardMigrationSdk>(relaxed = true) {
            coEvery { getMigrationState() } returns MigrationState.RequiresAttention(AttentionReason.TransferExpired)
        }
        val router = mockk<NavigationRouter>(relaxed = true)

        useCase(sdk = sdk, navigationRouter = router, pendingMigrationTorFailure = false).invoke()

        coVerify(exactly = 1) { router.replaceAll(HomeArgs, MigrationTransferInvalidArgs) }
    }

    @Test
    fun dueTransferWithoutBackgroundExecutionRoutesToTransferReview() = runTest {
        val now = Clock.System.now()
        val plan = planWithPendingTransfer((now - 1.minutes).epochSeconds)
        val sdk = mockk<OrchardMigrationSdk>(relaxed = true) {
            coEvery { hasInvalidTransfers() } returns false
            coEvery { hasOverdueTransfers() } returns false
        }
        val router = mockk<NavigationRouter>(relaxed = true)

        useCase(
            sdk = sdk,
            navigationRouter = router,
            savedPlan = plan,
            isBackgroundExecutionAvailable = false,
        ).invoke()

        coVerify(exactly = 1) { router.replaceAll(HomeArgs, MigrationTransferReviewArgs) }
    }

    @Test
    fun invalidTransfersTakePriorityOverReadyToSend() = runTest {
        val now = Clock.System.now()
        val plan = planWithPendingTransfer((now - 1.minutes).epochSeconds)
        val sdk = mockk<OrchardMigrationSdk>(relaxed = true) {
            coEvery { getMigrationState() } returns MigrationState.RequiresAttention(AttentionReason.InvalidTransfer("t1"))
        }
        val router = mockk<NavigationRouter>(relaxed = true)

        useCase(
            sdk = sdk,
            navigationRouter = router,
            savedPlan = plan,
            isBackgroundExecutionAvailable = false,
        ).invoke()

        coVerify(exactly = 1) { router.replaceAll(HomeArgs, MigrationTransferInvalidArgs) }
        coVerify(exactly = 0) { router.replaceAll(HomeArgs, MigrationTransferReviewArgs) }
    }

    @Test
    fun alreadyOverdueTransferTakesPriorityOverReadyToSend() = runTest {
        // Once the SDK itself counts it as overdue, the fuller Reschedule/Send-now recovery screen
        // owns it — the ready-to-send branch is only for the narrower "just became due" window.
        val now = Clock.System.now()
        val plan = planWithPendingTransfer((now - 1.minutes).epochSeconds)
        val sdk = mockk<OrchardMigrationSdk>(relaxed = true) {
            coEvery { hasInvalidTransfers() } returns false
            coEvery { hasOverdueTransfers() } returns true
            coEvery { getMigrationState() } returns MigrationState.InProgress(mockk(relaxed = true))
        }
        val router = mockk<NavigationRouter>(relaxed = true)

        useCase(
            sdk = sdk,
            navigationRouter = router,
            savedPlan = plan,
            isBackgroundExecutionAvailable = false,
        ).invoke()

        coVerify(exactly = 1) { router.replaceAll(HomeArgs, MigrationProgressArgs) }
        coVerify(exactly = 0) { router.replaceAll(HomeArgs, MigrationTransferReviewArgs) }
    }

    @Test
    fun backgroundExecutionAvailableFallsThroughToOverdueCheck() = runTest {
        val now = Clock.System.now()
        val plan = planWithPendingTransfer((now - 1.minutes).epochSeconds)
        val sdk = mockk<OrchardMigrationSdk>(relaxed = true) {
            coEvery { hasInvalidTransfers() } returns false
            coEvery { hasOverdueTransfers() } returns true
            coEvery { getMigrationState() } returns MigrationState.InProgress(mockk(relaxed = true))
        }
        val router = mockk<NavigationRouter>(relaxed = true)

        useCase(
            sdk = sdk,
            navigationRouter = router,
            savedPlan = plan,
            isBackgroundExecutionAvailable = true,
        ).invoke()

        coVerify(exactly = 1) { router.replaceAll(HomeArgs, MigrationProgressArgs) }
        coVerify(exactly = 0) { router.replaceAll(HomeArgs, MigrationTransferReviewArgs) }
    }

    @Test
    fun notYetDueTransferDoesNotRouteToTransferReview() = runTest {
        val now = Clock.System.now()
        val plan = planWithPendingTransfer((now + 30.minutes).epochSeconds)
        val sdk = mockk<OrchardMigrationSdk>(relaxed = true) {
            coEvery { hasInvalidTransfers() } returns false
            coEvery { hasOverdueTransfers() } returns false
            coEvery { getMigrationState() } returns MigrationState.InProgress(mockk(relaxed = true))
        }
        val router = mockk<NavigationRouter>(relaxed = true)

        useCase(
            sdk = sdk,
            navigationRouter = router,
            savedPlan = plan,
            isBackgroundExecutionAvailable = false,
        ).invoke()

        coVerify(exactly = 0) { router.replaceAll(HomeArgs, MigrationTransferReviewArgs) }
    }

    @Test
    fun noPendingTorFailureAndNoInvalidTransfersFallsThroughToOverdueCheck() = runTest {
        val sdk = mockk<OrchardMigrationSdk>(relaxed = true) {
            coEvery { hasInvalidTransfers() } returns false
            coEvery { hasOverdueTransfers() } returns true
        }
        val router = mockk<NavigationRouter>(relaxed = true)

        useCase(
            sdk = sdk,
            navigationRouter = router,
            pendingMigrationTorFailure = false,
            isBackgroundExecutionAvailable = true,
        ).invoke()

        coVerify(exactly = 1) { router.replaceAll(HomeArgs, MigrationProgressArgs) }
    }

    @Test
    fun noPendingTorFailureNoInvalidNoOverdueButCompleteRoutesToCompleteCelebration() = runTest {
        val sdk = mockk<OrchardMigrationSdk>(relaxed = true) {
            coEvery { hasInvalidTransfers() } returns false
            coEvery { hasOverdueTransfers() } returns false
            coEvery { getMigrationState() } returns MigrationState.Complete
        }
        val router = mockk<NavigationRouter>(relaxed = true)

        useCase(
            sdk = sdk,
            navigationRouter = router,
            pendingMigrationTorFailure = false,
            hasSeenMigrationComplete = false,
            savedPlan = mockk(relaxed = true),
            isBackgroundExecutionAvailable = true,
            orchardBalanceZatoshi = 0L,
        ).invoke()

        coVerify(exactly = 1) { router.replaceAll(HomeArgs, MigrationCompleteArgs) }
    }

    @Test
    fun notStartedWithStaleWriteAheadPlanClearsTheStalePlan() = runTest {
        // MigrationReviewVM persists the plan just before the irreversible SDK commit; if that commit
        // never happened the SDK stays NotStarted while a stale plan lingers. The SDK state is
        // authoritative, so the stale plan is discarded (and nothing is navigated).
        val plans = mockk<MigrationPlanRepository>(relaxed = true) {
            coEvery { load() } returns mockk(relaxed = true)
        }
        val sdk = mockk<OrchardMigrationSdk>(relaxed = true) {
            coEvery { hasInvalidTransfers() } returns false
            coEvery { hasOverdueTransfers() } returns false
            coEvery { getMigrationState() } returns MigrationState.NotStarted
        }
        val router = mockk<NavigationRouter>(relaxed = true)

        useCase(sdk = sdk, navigationRouter = router, migrationPlanRepository = plans).invoke()

        coVerify(exactly = 1) { plans.clear() }
        coVerify(exactly = 0) { router.replaceAll(any()) }
    }

    @Test
    fun notStartedWithNoPlanLeavesEverythingAlone() = runTest {
        val plans = mockk<MigrationPlanRepository>(relaxed = true) {
            coEvery { load() } returns null
        }
        val sdk = mockk<OrchardMigrationSdk>(relaxed = true) {
            coEvery { hasInvalidTransfers() } returns false
            coEvery { hasOverdueTransfers() } returns false
            coEvery { getMigrationState() } returns MigrationState.NotStarted
        }
        val router = mockk<NavigationRouter>(relaxed = true)

        useCase(sdk = sdk, navigationRouter = router, migrationPlanRepository = plans).invoke()

        coVerify(exactly = 0) { plans.clear() }
        coVerify(exactly = 0) { router.replaceAll(any()) }
    }

    @Test
    fun noWalletAvailableDoesNothing() = runTest {
        val router = mockk<NavigationRouter>(relaxed = true)

        useCase(sdk = null, navigationRouter = router).invoke()

        coVerify(exactly = 0) { router.replaceAll(any()) }
    }

    // ── Lane A reconciliation tests (Finding 3) ────────────────────────────────────────

    @Test
    fun laneAReconciliation_planExistsAndLaneInactive_schedulesLaneA() = runTest {
        // Finding 3: plan exists + isLaneAActive = false → migrationSyncScheduler.schedule called.
        val syncScheduler = mockk<MigrationSyncScheduler>(relaxed = true)
        val sdk = mockk<OrchardMigrationSdk>(relaxed = true) {
            coEvery { getMigrationState() } returns MigrationState.InProgress(mockk(relaxed = true))
            coEvery { hasOverdueTransfers() } returns false
        }

        useCase(
            sdk = sdk,
            navigationRouter = mockk(relaxed = true),
            savedPlan = mockk(relaxed = true),
            migrationSyncScheduler = syncScheduler,
            isLaneAActive = { false },
        ).invoke()

        // A short flat first arm — the worker's first run computes the precise boundary wake.
        verify { syncScheduler.schedule(any(), 60.seconds) }
    }

    @Test
    fun laneAReconciliation_planExistsAndLaneActive_doesNotScheduleLaneA() = runTest {
        // Finding 3: plan exists + isLaneAActive = true → migrationSyncScheduler.schedule NOT called.
        val syncScheduler = mockk<MigrationSyncScheduler>(relaxed = true)
        val sdk = mockk<OrchardMigrationSdk>(relaxed = true) {
            coEvery { getMigrationState() } returns MigrationState.InProgress(mockk(relaxed = true))
            coEvery { hasOverdueTransfers() } returns false
        }

        useCase(
            sdk = sdk,
            navigationRouter = mockk(relaxed = true),
            savedPlan = mockk(relaxed = true),
            migrationSyncScheduler = syncScheduler,
            isLaneAActive = { true },
        ).invoke()

        verify(exactly = 0) { syncScheduler.schedule(any(), any()) }
    }
}
