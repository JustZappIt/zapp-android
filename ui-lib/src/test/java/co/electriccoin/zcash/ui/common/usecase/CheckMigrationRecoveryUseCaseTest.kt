package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.MigrationState
import cash.z.ecc.android.sdk.OrchardMigrationSdk
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.model.migration.MigrationMode
import co.electriccoin.zcash.ui.common.model.migration.MigrationPlan
import co.electriccoin.zcash.ui.common.model.migration.MigrationTransfer
import co.electriccoin.zcash.ui.common.model.migration.MigrationTransferStatus
import co.electriccoin.zcash.ui.common.provider.HasSeenMigrationCompleteStorageProvider
import co.electriccoin.zcash.ui.common.provider.IsBackgroundExecutionAvailableProvider
import co.electriccoin.zcash.ui.common.repository.MigrationPlanRepository
import co.electriccoin.zcash.ui.screen.home.HomeArgs
import co.electriccoin.zcash.ui.screen.migration.invalid.MigrationTransferInvalidArgs
import co.electriccoin.zcash.ui.screen.migration.progress.MigrationProgressArgs
import co.electriccoin.zcash.ui.screen.migration.transferreview.MigrationTransferReviewArgs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

class CheckMigrationRecoveryUseCaseTest {
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
        sdk: OrchardMigrationSdk,
        navigationRouter: NavigationRouter,
        plan: MigrationPlan?,
        isBackgroundExecutionAvailable: Boolean,
    ) = CheckMigrationRecoveryUseCase(
        getOrchardMigrationSdk = mockk<GetOrchardMigrationSdkUseCase> {
            coEvery { this@mockk() } returns sdk
        },
        navigationRouter = navigationRouter,
        hasSeenMigrationCompleteStorageProvider = mockk<HasSeenMigrationCompleteStorageProvider>(relaxed = true) {
            coEvery { get() } returns false
        },
        migrationPlanRepository = mockk<MigrationPlanRepository>(relaxed = true) {
            coEvery { load() } returns plan
        },
        isBackgroundExecutionAvailableProvider = mockk<IsBackgroundExecutionAvailableProvider> {
            every { isAvailable() } returns isBackgroundExecutionAvailable
        },
    )

    @Test
    fun dueTransferWithoutBackgroundExecutionRoutesToTransferReview() = runTest {
        val now = Clock.System.now()
        val plan = planWithPendingTransfer((now - 1.minutes).epochSeconds)
        val sdk = mockk<OrchardMigrationSdk> {
            coEvery { hasInvalidTransfers() } returns false
            coEvery { hasOverdueTransfers() } returns false
        }
        val router = mockk<NavigationRouter>(relaxed = true)

        useCase(sdk, router, plan, isBackgroundExecutionAvailable = false)()

        coVerify(exactly = 1) { router.replaceAll(HomeArgs, MigrationTransferReviewArgs) }
    }

    @Test
    fun invalidTransfersTakePriorityOverReadyToSend() = runTest {
        val now = Clock.System.now()
        val plan = planWithPendingTransfer((now - 1.minutes).epochSeconds)
        val sdk = mockk<OrchardMigrationSdk> {
            coEvery { hasInvalidTransfers() } returns true
        }
        val router = mockk<NavigationRouter>(relaxed = true)

        useCase(sdk, router, plan, isBackgroundExecutionAvailable = false)()

        coVerify(exactly = 1) { router.replaceAll(HomeArgs, MigrationTransferInvalidArgs) }
        coVerify(exactly = 0) { router.replaceAll(HomeArgs, MigrationTransferReviewArgs) }
    }

    @Test
    fun alreadyOverdueTransferTakesPriorityOverReadyToSend() = runTest {
        // Once the SDK itself counts it as overdue, the fuller Reschedule/Send-now recovery screen
        // owns it — the ready-to-send branch is only for the narrower "just became due" window.
        val now = Clock.System.now()
        val plan = planWithPendingTransfer((now - 1.minutes).epochSeconds)
        val sdk = mockk<OrchardMigrationSdk> {
            coEvery { hasInvalidTransfers() } returns false
            coEvery { hasOverdueTransfers() } returns true
            coEvery { getMigrationState() } returns MigrationState.InProgress(mockk(relaxed = true))
        }
        val router = mockk<NavigationRouter>(relaxed = true)

        useCase(sdk, router, plan, isBackgroundExecutionAvailable = false)()

        coVerify(exactly = 1) { router.replaceAll(HomeArgs, MigrationProgressArgs) }
        coVerify(exactly = 0) { router.replaceAll(HomeArgs, MigrationTransferReviewArgs) }
    }

    @Test
    fun backgroundExecutionAvailableFallsThroughToOverdueCheck() = runTest {
        val now = Clock.System.now()
        val plan = planWithPendingTransfer((now - 1.minutes).epochSeconds)
        val sdk = mockk<OrchardMigrationSdk> {
            coEvery { hasInvalidTransfers() } returns false
            coEvery { hasOverdueTransfers() } returns true
            coEvery { getMigrationState() } returns MigrationState.InProgress(mockk(relaxed = true))
        }
        val router = mockk<NavigationRouter>(relaxed = true)

        useCase(sdk, router, plan, isBackgroundExecutionAvailable = true)()

        coVerify(exactly = 1) { router.replaceAll(HomeArgs, MigrationProgressArgs) }
        coVerify(exactly = 0) { router.replaceAll(HomeArgs, MigrationTransferReviewArgs) }
    }

    @Test
    fun notYetDueTransferDoesNotRouteToTransferReview() = runTest {
        val now = Clock.System.now()
        val plan = planWithPendingTransfer((now + 30.minutes).epochSeconds)
        val sdk = mockk<OrchardMigrationSdk> {
            coEvery { hasInvalidTransfers() } returns false
            coEvery { hasOverdueTransfers() } returns false
            coEvery { getMigrationState() } returns MigrationState.InProgress(mockk(relaxed = true))
        }
        val router = mockk<NavigationRouter>(relaxed = true)

        useCase(sdk, router, plan, isBackgroundExecutionAvailable = false)()

        coVerify(exactly = 0) { router.replaceAll(HomeArgs, MigrationTransferReviewArgs) }
    }
}
