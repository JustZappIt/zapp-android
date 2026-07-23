package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.MigrationProgress
import cash.z.ecc.android.sdk.MigrationState
import co.electriccoin.zcash.ui.common.model.migration.MIGRATION_DUST_THRESHOLD_ZATOSHI
import co.electriccoin.zcash.ui.common.model.migration.MigrationMode
import co.electriccoin.zcash.ui.common.model.migration.MigrationPlan
import co.electriccoin.zcash.ui.common.model.migration.MigrationTransfer
import co.electriccoin.zcash.ui.common.model.migration.MigrationTransferStatus
import co.electriccoin.zcash.ui.common.repository.HomeMessageData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

class GetHomeMessageUseCaseMigrationTest {
    private fun plan(id: String = "p1") =
        MigrationPlan(
            id = id,
            createdAtEpochSeconds = 0L,
            transfers = emptyList(),
            mode = MigrationMode.AUTOMATIC,
        )

    private fun planWithPendingTransfer(scheduledAtEpochSeconds: Long) =
        MigrationPlan(
            id = "p-ready",
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

    @Test
    fun freshWalletWithNoPlanAndNoBalanceShowsNothing() {
        val result = migrationMessageFor(
            sdkState = null,
            plan = null,
            hasSeenComplete = false,
            orchardBalanceZatoshi = 0L,
        )
        assertNull(result)
    }

    @Test
    fun freshWalletWithBalanceAndNoPlanShowsRequired() {
        val result = migrationMessageFor(
            sdkState = null,
            plan = null,
            hasSeenComplete = false,
            orchardBalanceZatoshi = MIGRATION_DUST_THRESHOLD_ZATOSHI + 100_000L,
        )
        assertEquals(HomeMessageData.Migration(null), result)
    }

    @Test
    fun freshWalletWithDustBalanceAndNoPlanShowsNothing() {
        // Entry-banner gating uses the same dust threshold as completion gating (spec §9.9) — a
        // balance at or below it never needs a migration prompt of its own.
        val result = migrationMessageFor(
            sdkState = null,
            plan = null,
            hasSeenComplete = false,
            orchardBalanceZatoshi = MIGRATION_DUST_THRESHOLD_ZATOSHI,
        )
        assertNull(result)
    }

    @Test
    fun inProgressShowsInProgressBannerRegardlessOfBalance() {
        val migrationProgress = MigrationProgress(
            completedTransfers = 1,
            totalTransfers = 3,
            remainingOrchardZatoshi = 500_000L,
            nextTransferReadyAtHeight = null,
        )
        val result = migrationMessageFor(
            sdkState = MigrationState.InProgress(migrationProgress),
            plan = plan(),
            hasSeenComplete = false,
            orchardBalanceZatoshi = 500_000L,
        )
        assertEquals(HomeMessageData.Migration(plan()), result)
    }

    @Test
    fun completeWithUnacknowledgedPlanShowsCompleteBanner() {
        val result = migrationMessageFor(
            sdkState = MigrationState.Complete,
            plan = plan(),
            hasSeenComplete = false,
            orchardBalanceZatoshi = MIGRATION_DUST_THRESHOLD_ZATOSHI - 1L,
        )
        assertEquals(HomeMessageData.Migration(plan(), isComplete = true), result)
    }

    @Test
    fun completeWithUnacknowledgedPlanButResidualAboveThresholdDoesNotShowCompleteBanner() {
        // Pins the fix for the multi-round Keystone bug: the SDK's own MigrationState reports
        // Complete as soon as the *current* round's transfers are all mined, even with a large
        // residual balance well above the dust threshold still needing another round. Showing the
        // one-time completion banner (and its "Lock balance" option) at that point would be wrong.
        val result = migrationMessageFor(
            sdkState = MigrationState.Complete,
            plan = plan(),
            hasSeenComplete = false,
            orchardBalanceZatoshi = 500_000L,
        )
        assertNull(result)
    }

    @Test
    fun completeWithClearedPlanAndResidualBalanceReEvaluatesToRequired() {
        // Simulates the auto-continuation case: Task 7 clears the plan (without setting
        // hasSeenComplete) when a round finishes but residual balance still needs another round.
        // The SDK's own MigrationState is still Complete at this point (it only advances once the
        // next round is actually committed) — the plan==null check must take priority over it.
        val result = migrationMessageFor(
            sdkState = MigrationState.Complete,
            plan = null,
            hasSeenComplete = false,
            orchardBalanceZatoshi = 300_000L,
        )
        assertEquals(HomeMessageData.Migration(null), result)
    }

    @Test
    fun completeWithClearedPlanAndZeroBalanceShowsNothing() {
        // Simulates the terminal case: Task 7 clears the plan and leaves hasSeenComplete false only
        // when there's still balance to migrate — if balance is genuinely zero, the seen flag would
        // have been set instead (see completeAcknowledgedShowsNothing below), but this pins down
        // that even an unacknowledged, cleared-plan state shows nothing once balance is zero.
        val result = migrationMessageFor(
            sdkState = MigrationState.Complete,
            plan = null,
            hasSeenComplete = false,
            orchardBalanceZatoshi = 0L,
        )
        assertNull(result)
    }

    @Test
    fun completeAcknowledgedShowsNothing() {
        val result = migrationMessageFor(
            sdkState = MigrationState.Complete,
            plan = plan(),
            hasSeenComplete = true,
            orchardBalanceZatoshi = 0L,
        )
        assertNull(result)
    }

    // --- Spec §6.4 "Transfer Ready to Send" ---

    @Test
    fun dueTransferWithoutBackgroundExecutionAndNotOverdueShowsReadyToSend() {
        val now = Clock.System.now()
        val readyPlan = planWithPendingTransfer((now - 1.minutes).epochSeconds)
        val migrationProgress = MigrationProgress(1, 3, 100_000L, null)

        val result = migrationMessageFor(
            sdkState = MigrationState.InProgress(migrationProgress),
            plan = readyPlan,
            hasSeenComplete = false,
            orchardBalanceZatoshi = 100_000L,
            isBackgroundExecutionAvailable = false,
            hasOverdueTransfers = false,
            now = now,
        )

        assertEquals(HomeMessageData.Migration(readyPlan, isReadyToSend = true), result)
    }

    @Test
    fun dueTransferButBackgroundExecutionAvailableShowsRegularInProgress() {
        // Background execution can run the WorkManager job itself — no need for the fallback
        // ready-to-send banner in that case.
        val now = Clock.System.now()
        val readyPlan = planWithPendingTransfer((now - 1.minutes).epochSeconds)
        val migrationProgress = MigrationProgress(1, 3, 100_000L, null)

        val result = migrationMessageFor(
            sdkState = MigrationState.InProgress(migrationProgress),
            plan = readyPlan,
            hasSeenComplete = false,
            orchardBalanceZatoshi = 100_000L,
            isBackgroundExecutionAvailable = true,
            hasOverdueTransfers = false,
            now = now,
        )

        assertEquals(HomeMessageData.Migration(readyPlan), result)
    }

    @Test
    fun dueTransferAlreadyOverdueShowsRegularInProgressNotReadyToSend() {
        // Once the SDK counts it as overdue, MigrationProgressVM's Reschedule/Send-now flow takes
        // over — the ready-to-send banner is only for the narrower "just became due" window before
        // that.
        val now = Clock.System.now()
        val readyPlan = planWithPendingTransfer((now - 1.minutes).epochSeconds)
        val migrationProgress = MigrationProgress(1, 3, 100_000L, null)

        val result = migrationMessageFor(
            sdkState = MigrationState.InProgress(migrationProgress),
            plan = readyPlan,
            hasSeenComplete = false,
            orchardBalanceZatoshi = 100_000L,
            isBackgroundExecutionAvailable = false,
            hasOverdueTransfers = true,
            now = now,
        )

        assertEquals(HomeMessageData.Migration(readyPlan), result)
    }

    @Test
    fun notYetDueTransferWithoutBackgroundExecutionShowsRegularInProgress() {
        val now = Clock.System.now()
        val notYetDuePlan = planWithPendingTransfer((now + 30.minutes).epochSeconds)
        val migrationProgress = MigrationProgress(1, 3, 100_000L, null)

        val result = migrationMessageFor(
            sdkState = MigrationState.InProgress(migrationProgress),
            plan = notYetDuePlan,
            hasSeenComplete = false,
            orchardBalanceZatoshi = 100_000L,
            isBackgroundExecutionAvailable = false,
            hasOverdueTransfers = false,
            now = now,
        )

        assertEquals(HomeMessageData.Migration(notYetDuePlan), result)
    }
}
