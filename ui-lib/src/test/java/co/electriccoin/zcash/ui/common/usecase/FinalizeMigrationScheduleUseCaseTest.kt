package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.MigrationSchedule
import cash.z.ecc.android.sdk.OrchardMigrationSdk
import cash.z.ecc.android.sdk.TransferProposal
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import co.electriccoin.zcash.ui.common.model.migration.MigrationKeystoneRound
import co.electriccoin.zcash.ui.common.model.migration.MigrationMode
import co.electriccoin.zcash.ui.common.model.migration.MigrationPlan
import co.electriccoin.zcash.ui.common.repository.MigrationPlanRepository
import co.electriccoin.zcash.work.MigrationScheduler
import co.electriccoin.zcash.work.MigrationSyncScheduler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FinalizeMigrationScheduleUseCaseTest {
    private fun schedule() =
        MigrationSchedule(
            transfers = listOf(
                TransferProposal(
                    id = "t1",
                    amountZatoshi = 100_000L,
                    anchorHeight = 100L,
                    nextExecutableAfterHeight = 200L,
                    expiryHeight = 300L,
                )
            ),
            estimatedDurationHours = 1,
            proposalHandle = 0L,
        )

    @Test
    fun keystoneAccountPopulatesKeystoneRoundFromFreshEstimate() = runTest {
        val plans = mockk<MigrationPlanRepository>(relaxed = true)
        val savedPlan = slot<MigrationPlan>()
        val sdk = mockk<OrchardMigrationSdk> {
            coEvery { estimateMigrationRunCount() } returns 3
        }
        val useCase = FinalizeMigrationScheduleUseCase(
            migrationPlanRepository = plans,
            migrationScheduler = mockk<MigrationScheduler>(relaxed = true),
            migrationSyncScheduler = mockk<MigrationSyncScheduler>(relaxed = true),
            navigationRouter = mockk<NavigationRouter>(relaxed = true),
            getOrchardMigrationSdk = mockk<GetOrchardMigrationSdkUseCase> {
                coEvery { this@mockk() } returns sdk
            },
            getSelectedWalletAccount = mockk<GetSelectedWalletAccountUseCase> {
                coEvery { this@mockk() } returns mockk<KeystoneAccount>(relaxed = true)
            },
        )

        useCase(schedule(), MigrationMode.AUTOMATIC)

        coVerify { plans.save(capture(savedPlan)) }
        assertEquals(MigrationKeystoneRound(current = 1, total = 3), savedPlan.captured.keystoneRound)
    }

    @Test
    fun nonKeystoneAccountLeavesKeystoneRoundNull() = runTest {
        val plans = mockk<MigrationPlanRepository>(relaxed = true)
        val savedPlan = slot<MigrationPlan>()
        val useCase = FinalizeMigrationScheduleUseCase(
            migrationPlanRepository = plans,
            migrationScheduler = mockk<MigrationScheduler>(relaxed = true),
            migrationSyncScheduler = mockk<MigrationSyncScheduler>(relaxed = true),
            navigationRouter = mockk<NavigationRouter>(relaxed = true),
            getOrchardMigrationSdk = mockk<GetOrchardMigrationSdkUseCase>(relaxed = true),
            getSelectedWalletAccount = mockk<GetSelectedWalletAccountUseCase> {
                coEvery { this@mockk() } returns mockk<ZashiAccount>(relaxed = true)
            },
        )

        useCase(schedule(), MigrationMode.AUTOMATIC)

        coVerify { plans.save(capture(savedPlan)) }
        assertNull(savedPlan.captured.keystoneRound)
    }
}
