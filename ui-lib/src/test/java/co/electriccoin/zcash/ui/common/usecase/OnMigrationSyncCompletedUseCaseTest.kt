package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.OrchardMigrationSdk
import co.electriccoin.zcash.ui.common.provider.LastNetworkActivityStorageProvider
import co.electriccoin.zcash.ui.common.provider.MigrationNotifier
import co.electriccoin.zcash.work.MigrationScheduler
import co.electriccoin.zcash.work.MigrationSyncScheduler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class OnMigrationSyncCompletedUseCaseTest {

    private fun useCase(
        sdk: OrchardMigrationSdk,
        lastNetworkActivity: LastNetworkActivityStorageProvider = mockk(relaxed = true),
        migrationNotifier: MigrationNotifier = mockk(relaxed = true),
        migrationScheduler: MigrationScheduler = mockk(relaxed = true),
        migrationSyncScheduler: MigrationSyncScheduler = mockk(relaxed = true),
    ) = OnMigrationSyncCompletedUseCase(
        getOrchardMigrationSdk = mockk<GetOrchardMigrationSdkUseCase> {
            coEvery { this@mockk(any()) } returns sdk
        },
        lastNetworkActivity = lastNetworkActivity,
        migrationNotifier = migrationNotifier,
        migrationScheduler = migrationScheduler,
        migrationSyncScheduler = migrationSyncScheduler,
    )

    @Test
    fun happyPathCallsFinalizeThenReconcileThenStamp() = runTest {
        val sdk = mockk<OrchardMigrationSdk>(relaxed = true) {
            coEvery { finalizeReadyTransfers() } returns 0
            coEvery { reconcileInvalidations() } returns false
        }
        val lastNetworkActivity = mockk<LastNetworkActivityStorageProvider>(relaxed = true)
        val migrationNotifier = mockk<MigrationNotifier>(relaxed = true)
        val migrationScheduler = mockk<MigrationScheduler>(relaxed = true)
        val migrationSyncScheduler = mockk<MigrationSyncScheduler>(relaxed = true)

        useCase(
            sdk = sdk,
            lastNetworkActivity = lastNetworkActivity,
            migrationNotifier = migrationNotifier,
            migrationScheduler = migrationScheduler,
            migrationSyncScheduler = migrationSyncScheduler,
        ).invoke("account-key-1")

        coVerifyOrder {
            sdk.finalizeReadyTransfers()
            sdk.reconcileInvalidations()
            lastNetworkActivity.stampNow()
        }
        coVerify(exactly = 0) { migrationNotifier.notifyMigrationPlanInvalid(any()) }
        verify(exactly = 0) { migrationScheduler.cancel(any()) }
        verify(exactly = 0) { migrationSyncScheduler.cancel(any()) }
    }

    @Test
    fun invalidationPathNotifiesCancelsBothLanesAndStillStamps() = runTest {
        val sdk = mockk<OrchardMigrationSdk>(relaxed = true) {
            coEvery { finalizeReadyTransfers() } returns 0
            coEvery { reconcileInvalidations() } returns true
        }
        val lastNetworkActivity = mockk<LastNetworkActivityStorageProvider>(relaxed = true)
        val migrationNotifier = mockk<MigrationNotifier>(relaxed = true)
        val migrationScheduler = mockk<MigrationScheduler>(relaxed = true)
        val migrationSyncScheduler = mockk<MigrationSyncScheduler>(relaxed = true)

        useCase(
            sdk = sdk,
            lastNetworkActivity = lastNetworkActivity,
            migrationNotifier = migrationNotifier,
            migrationScheduler = migrationScheduler,
            migrationSyncScheduler = migrationSyncScheduler,
        ).invoke("account-key-2")

        coVerify(exactly = 1) { migrationNotifier.notifyMigrationPlanInvalid("account-key-2") }
        verify(exactly = 1) { migrationScheduler.cancel("account-key-2") }
        verify(exactly = 1) { migrationSyncScheduler.cancel("account-key-2") }
        coVerify(exactly = 1) { lastNetworkActivity.stampNow() }
    }
}
