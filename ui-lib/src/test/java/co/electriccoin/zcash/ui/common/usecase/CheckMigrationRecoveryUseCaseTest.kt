package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.MigrationState
import cash.z.ecc.android.sdk.OrchardMigrationSdk
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.model.migration.MigrationPlan
import co.electriccoin.zcash.ui.common.provider.HasSeenMigrationCompleteStorageProvider
import co.electriccoin.zcash.ui.common.provider.PendingMigrationTorFailureStorageProvider
import co.electriccoin.zcash.ui.common.repository.MigrationPlanRepository
import co.electriccoin.zcash.ui.screen.home.HomeArgs
import co.electriccoin.zcash.ui.screen.migration.complete.MigrationCompleteArgs
import co.electriccoin.zcash.ui.screen.migration.invalid.MigrationTransferInvalidArgs
import co.electriccoin.zcash.ui.screen.migration.progress.MigrationProgressArgs
import co.electriccoin.zcash.ui.screen.migration.sending.MigrationSendingArgs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class CheckMigrationRecoveryUseCaseTest {

    private fun useCase(
        sdk: OrchardMigrationSdk?,
        navigationRouter: NavigationRouter,
        pendingMigrationTorFailure: Boolean = false,
        hasSeenMigrationComplete: Boolean = false,
        savedPlan: MigrationPlan? = mockk(relaxed = true),
    ) = CheckMigrationRecoveryUseCase(
        getOrchardMigrationSdk = mockk<GetOrchardMigrationSdkUseCase> {
            coEvery { this@mockk() } returns sdk
        },
        navigationRouter = navigationRouter,
        hasSeenMigrationCompleteStorageProvider = mockk<HasSeenMigrationCompleteStorageProvider> {
            coEvery { get() } returns hasSeenMigrationComplete
        },
        migrationPlanRepository = mockk<MigrationPlanRepository> {
            coEvery { load() } returns savedPlan
        },
        pendingMigrationTorFailureStorageProvider = mockk<PendingMigrationTorFailureStorageProvider> {
            coEvery { get() } returns pendingMigrationTorFailure
        },
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
            coEvery { hasInvalidTransfers() } returns true
        }
        val router = mockk<NavigationRouter>(relaxed = true)

        useCase(sdk = sdk, navigationRouter = router, pendingMigrationTorFailure = false).invoke()

        coVerify(exactly = 1) { router.replaceAll(HomeArgs, MigrationTransferInvalidArgs) }
    }

    @Test
    fun noPendingTorFailureAndNoInvalidTransfersFallsThroughToOverdueCheck() = runTest {
        val sdk = mockk<OrchardMigrationSdk>(relaxed = true) {
            coEvery { hasInvalidTransfers() } returns false
            coEvery { hasOverdueTransfers() } returns true
        }
        val router = mockk<NavigationRouter>(relaxed = true)

        useCase(sdk = sdk, navigationRouter = router, pendingMigrationTorFailure = false).invoke()

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
        ).invoke()

        coVerify(exactly = 1) { router.replaceAll(HomeArgs, MigrationCompleteArgs) }
    }

    @Test
    fun noWalletAvailableDoesNothing() = runTest {
        val router = mockk<NavigationRouter>(relaxed = true)

        useCase(sdk = null, navigationRouter = router).invoke()

        coVerify(exactly = 0) { router.replaceAll(any()) }
    }
}
