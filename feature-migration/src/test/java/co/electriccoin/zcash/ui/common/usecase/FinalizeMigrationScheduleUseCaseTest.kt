package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.MigrationSchedule
import cash.z.ecc.android.sdk.OrchardMigrationSdk
import cash.z.ecc.android.sdk.PreparationStep
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
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

class FinalizeMigrationScheduleUseCaseTest {
    private fun schedule() =
        MigrationSchedule(
            transfers =
                listOf(
                    TransferProposal(
                        id = 11L,
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
    fun invokeSchedulesLaneASyncScheduler() =
        runTest {
            // Lane A's first arm is a short flat delay — the worker's first run reads the freshly
            // committed engine states and computes the precise boundary-driven wake itself.
            val syncScheduler = mockk<MigrationSyncScheduler>(relaxed = true)
            val useCase =
                FinalizeMigrationScheduleUseCase(
                    migrationPlanRepository = mockk(relaxed = true),
                    migrationScheduler = mockk<MigrationScheduler>(relaxed = true),
                    migrationSyncScheduler = syncScheduler,
                    navigationRouter = mockk<NavigationRouter>(relaxed = true),
                    getOrchardMigrationSdk = mockk<GetOrchardMigrationSdkUseCase>(relaxed = true),
                    getSelectedWalletAccount =
                        mockk<GetSelectedWalletAccountUseCase> {
                            coEvery { this@mockk() } returns mockk<ZashiAccount>(relaxed = true)
                        },
                    synchronizerProvider = mockk(relaxed = true),
                )

            useCase(schedule(), MigrationMode.AUTOMATIC)

            verify { syncScheduler.schedule(any(), 60.seconds) }
        }

    @Test
    fun keystoneAccountPopulatesKeystoneRoundFromFreshEstimate() =
        runTest {
            val plans = mockk<MigrationPlanRepository>(relaxed = true)
            val savedPlan = slot<MigrationPlan>()
            val sdk =
                mockk<OrchardMigrationSdk> {
                    coEvery { estimateMigrationRunCount() } returns 3
                    coEvery { estimatedSecondsPerBlock() } returns 75L
                }
            val useCase =
                FinalizeMigrationScheduleUseCase(
                    migrationPlanRepository = plans,
                    migrationScheduler = mockk<MigrationScheduler>(relaxed = true),
                    migrationSyncScheduler = mockk<MigrationSyncScheduler>(relaxed = true),
                    navigationRouter = mockk<NavigationRouter>(relaxed = true),
                    getOrchardMigrationSdk =
                        mockk<GetOrchardMigrationSdkUseCase> {
                            coEvery { this@mockk() } returns sdk
                        },
                    getSelectedWalletAccount =
                        mockk<GetSelectedWalletAccountUseCase> {
                            coEvery { this@mockk() } returns mockk<KeystoneAccount>(relaxed = true)
                        },
                    synchronizerProvider = mockk(relaxed = true),
                )

            useCase(schedule(), MigrationMode.AUTOMATIC)

            coVerify { plans.save(capture(savedPlan)) }
            assertEquals(MigrationKeystoneRound(current = 1, total = 3), savedPlan.captured.keystoneRound)
        }

    @Test
    fun `delay targets earliest step including preparations`() {
        val sched =
            MigrationSchedule(
                transfers =
                    listOf(
                        TransferProposal(
                            id = 4,
                            amountZatoshi = 1,
                            anchorHeight = 4219036,
                            nextExecutableAfterHeight = 4219108,
                            expiryHeight = 9_999_999
                        )
                    ),
                preparations =
                    listOf(
                        PreparationStep(id = 1, layer = 0, index = 1, broadcastHeight = 4219043, dependsOn = emptyList())
                    ),
                estimatedDurationHours = 1,
                proposalHandle = 1,
            )
        // exposed for test: delayUntilFirstStep(sched, secondsPerBlock=28, tipHeight=4219036)
        val useCase =
            FinalizeMigrationScheduleUseCase(
                migrationPlanRepository = mockk(relaxed = true),
                migrationScheduler = mockk(relaxed = true),
                migrationSyncScheduler = mockk(relaxed = true),
                navigationRouter = mockk(relaxed = true),
                getOrchardMigrationSdk = mockk(relaxed = true),
                getSelectedWalletAccount =
                    mockk<GetSelectedWalletAccountUseCase> {
                        coEvery { this@mockk() } returns mockk<ZashiAccount>(relaxed = true)
                    },
                synchronizerProvider = mockk(relaxed = true),
            )
        val d = useCase.delayUntilFirstStep(sched, secondsPerBlock = 28, tipHeight = 4219036)
        assertEquals((7 * 28).seconds, d) // 4219043-4219036 = 7 blocks
    }

    @Test
    fun nonKeystoneAccountLeavesKeystoneRoundNull() =
        runTest {
            val plans = mockk<MigrationPlanRepository>(relaxed = true)
            val savedPlan = slot<MigrationPlan>()
            val useCase =
                FinalizeMigrationScheduleUseCase(
                    migrationPlanRepository = plans,
                    migrationScheduler = mockk<MigrationScheduler>(relaxed = true),
                    migrationSyncScheduler = mockk<MigrationSyncScheduler>(relaxed = true),
                    navigationRouter = mockk<NavigationRouter>(relaxed = true),
                    getOrchardMigrationSdk = mockk<GetOrchardMigrationSdkUseCase>(relaxed = true),
                    getSelectedWalletAccount =
                        mockk<GetSelectedWalletAccountUseCase> {
                            coEvery { this@mockk() } returns mockk<ZashiAccount>(relaxed = true)
                        },
                    synchronizerProvider = mockk(relaxed = true),
                )

            useCase(schedule(), MigrationMode.AUTOMATIC)

            coVerify { plans.save(capture(savedPlan)) }
            assertNull(savedPlan.captured.keystoneRound)
        }
}
