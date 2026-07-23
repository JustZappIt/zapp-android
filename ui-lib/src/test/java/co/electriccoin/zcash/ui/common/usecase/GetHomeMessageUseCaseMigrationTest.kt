package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.AttentionReason
import cash.z.ecc.android.sdk.MigrationProgress
import cash.z.ecc.android.sdk.MigrationState
import co.electriccoin.zcash.ui.common.model.migration.MigrationAttentionKind
import co.electriccoin.zcash.ui.common.model.migration.MigrationMode
import co.electriccoin.zcash.ui.common.model.migration.MigrationPlan
import co.electriccoin.zcash.ui.common.repository.HomeMessageData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GetHomeMessageUseCaseMigrationTest {
    private fun plan(id: String = "p1") =
        MigrationPlan(
            id = id,
            createdAtEpochSeconds = 0L,
            transfers = emptyList(),
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
            orchardBalanceZatoshi = 100_000L,
        )
        assertEquals(HomeMessageData.Migration(null), result)
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
            orchardBalanceZatoshi = 200_000L,
        )
        assertEquals(HomeMessageData.Migration(plan(), isComplete = true), result)
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

    // Spec §6.2/§6.3 — the home banner must distinguish the two RequiresAttention causes instead
    // of returning null for both (the pre-fix behavior, which left the user with nothing on Home
    // if they backed out of the forced full-screen redirect).

    @Test
    fun requiresAttentionInvalidTransferShowsPlanUpdateBannerWithNoRange() {
        val result = migrationMessageFor(
            sdkState = MigrationState.RequiresAttention(AttentionReason.InvalidTransfer("t1")),
            plan = plan(),
            hasSeenComplete = false,
            orchardBalanceZatoshi = 300_000L,
        )
        assertEquals(
            HomeMessageData.Migration(plan(), attentionKind = MigrationAttentionKind.PLAN_UPDATE, attentionRangeText = null),
            result,
        )
    }

    @Test
    fun requiresAttentionTransferExpiredShowsTransferExpiredBannerWithPrecomputedRange() {
        val result = migrationMessageFor(
            sdkState = MigrationState.RequiresAttention(AttentionReason.TransferExpired),
            plan = plan(),
            hasSeenComplete = false,
            orchardBalanceZatoshi = 300_000L,
            attentionKind = MigrationAttentionKind.TRANSFER_EXPIRED,
            attentionRangeText = "3–5",
        )
        assertEquals(
            HomeMessageData.Migration(plan(), attentionKind = MigrationAttentionKind.TRANSFER_EXPIRED, attentionRangeText = "3–5"),
            result,
        )
    }

    @Test
    fun requiresAttentionWithNoPlanFallsThroughToOrdinaryLogicInsteadOfCrashing() {
        // Defensive case — RequiresAttention in practice always implies a plan/schedule already
        // existed, but must not NPE or otherwise misbehave if it's somehow null.
        val result = migrationMessageFor(
            sdkState = MigrationState.RequiresAttention(AttentionReason.TransferExpired),
            plan = null,
            hasSeenComplete = false,
            orchardBalanceZatoshi = 300_000L,
        )
        assertEquals(HomeMessageData.Migration(null), result)
    }
}
