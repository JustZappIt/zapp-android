package co.electriccoin.zcash.ui.common.model.migration.sim

import android.content.Context
import cash.z.ecc.android.sdk.MigrationState
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.provider.PendingMigrationTorFailureStorageProvider
import co.electriccoin.zcash.ui.common.usecase.CheckMigrationRecoveryUseCase
import co.electriccoin.zcash.ui.common.usecase.GetOrchardMigrationSdkUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSelectedWalletAccountUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Drives the real [CheckMigrationRecoveryUseCase] — the app-open re-entry router — against the
 * shared stateful [FakeOrchardMigrationSdk] instead of the ad-hoc `mockk` SDK the existing
 * [co.electriccoin.zcash.ui.common.usecase.CheckMigrationRecoveryUseCaseTest] uses. The difference
 * is that `getMigrationState()` is computed from a real seeded migration plan, so the recovery
 * decision is exercised against a self-consistent in-progress world.
 *
 * What is asserted:
 *  - A HEALTHY in-progress migration consults the worker-active check (revival would fire if the
 *    worker were absent) and does NOT auto-navigate (Task 6: only a pending Tor failure
 *    auto-navigates on app-open).
 *  - A completed migration with no saved plan skips reconciliation entirely.
 *
 * Scope note: the revival branch itself calls `co.electriccoin.zcash.work.MigrationScheduler(context)`
 * directly (not injected), whose init touches AlarmManager and whose `schedule` calls
 * `WorkManager.getInstance` — neither works under a plain unit-test `Context` mock. So these tests
 * assert the injectable worker-active decision and the FakeSdk-derived state (InProgress) that the
 * revival branch keys on; the revival itself was verified live (2026-07-29 reinstall run).
 */
class MigrationBackgroundRecoveryScenarioTest {
    @BeforeTest
    fun resetThrottle() {
        CheckMigrationRecoveryUseCase.resetRunThrottleForTests()
    }

    private companion object {
        const val ANCHOR: Long = 4_000_000L
        const val PREP_ID: Long = 1L
        const val TRANSFER_A: Long = 20L
        const val TRANSFER_B: Long = 21L
    }

    /** An in-progress migration: one transfer already sent, one still pending. */
    private fun inProgressDriver(): MigrationSimDriver {
        val driver = MigrationSimDriver()
        driver.seedPlan(
            preparations =
                listOf(
                    MigrationSimDriver.SimPrep(id = PREP_ID, layer = 0, scheduledHeight = ANCHOR - 40L),
                ),
            transfers =
                listOf(
                    MigrationSimDriver.SimTransfer(
                        id = TRANSFER_A,
                        scheduledHeight = ANCHOR + 5L,
                        anchorBoundary = ANCHOR,
                        dependsOn = listOf(PREP_ID),
                    ),
                    MigrationSimDriver.SimTransfer(
                        id = TRANSFER_B,
                        scheduledHeight = ANCHOR + 15L,
                        anchorBoundary = ANCHOR,
                        dependsOn = listOf(PREP_ID),
                    ),
                ),
            startTip = ANCHOR - 40L,
        )
        driver.mine(id = PREP_ID, height = ANCHOR - 2L)
        driver.setTip(ANCHOR + 20L)
        return driver
    }

    private fun useCase(
        driver: MigrationSimDriver,
        navigationRouter: NavigationRouter,
        pendingMigrationTorFailure: Boolean = false,
        isWorkerActive: suspend (String) -> Boolean = { true },
    ) = CheckMigrationRecoveryUseCase(
        getOrchardMigrationSdk =
            mockk<GetOrchardMigrationSdkUseCase> {
                coEvery { this@mockk() } returns driver.sdk
            },
        navigationRouter = navigationRouter,
        pendingMigrationTorFailureStorageProvider =
            mockk<PendingMigrationTorFailureStorageProvider> {
                coEvery { get() } returns pendingMigrationTorFailure
            },
        getSelectedWalletAccount = mockk<GetSelectedWalletAccountUseCase>(relaxed = true),
        context = mockk<Context>(relaxed = true),
        isWorkerActive = isWorkerActive,
    )

    @Test
    fun `healthy in-progress migration consults the worker-active check and does not auto-navigate`() =
        runTest {
            val driver = inProgressDriver()
            // Precondition sanity: the fake really is InProgress (not Complete/RequiresAttention).
            assertTrue(driver.sdk.getMigrationState() is MigrationState.InProgress)

            val router = mockk<NavigationRouter>(relaxed = true)
            var workerChecked = false

            useCase(
                driver = driver,
                navigationRouter = router,
                isWorkerActive = {
                    workerChecked = true
                    true
                },
            ).invoke()

            // The reconciliation block ran against the live in-progress engine state.
            assertTrue(workerChecked, "an in-progress migration must check whether the worker chain is alive")
            // A healthy in-progress migration must NOT hijack the screen on app-open (Task 6).
            coVerify(exactly = 0) { router.replaceAll(any()) }
        }

    @Test
    fun `a completed migration skips reconciliation and does not navigate`() =
        runTest {
            // Drain the plan to Complete, then app-open: the engine is no longer InProgress, so with no
            // saved plan there is nothing to reconcile and nothing to navigate.
            val driver = inProgressDriver()
            val opts =
                cash.z.ecc.android.sdk
                    .NetworkPrivacyOptions(useTor = false)
            driver.sdk.finalizeReadyTransfers()
            driver.sdk.executeNextPendingTransfer(opts, useEstimatedTip = true)
            driver.sdk.executeNextPendingTransfer(opts, useEstimatedTip = true)
            assertTrue(driver.sdk.getMigrationState() is MigrationState.Complete)

            val router = mockk<NavigationRouter>(relaxed = true)
            var workerChecked = false

            useCase(
                driver = driver,
                navigationRouter = router,
                isWorkerActive = {
                    workerChecked = true
                    true
                },
            ).invoke()

            assertFalse(workerChecked, "a completed migration must not consult the worker-active check")
            coVerify(exactly = 0) { router.replaceAll(any()) }
        }
}
