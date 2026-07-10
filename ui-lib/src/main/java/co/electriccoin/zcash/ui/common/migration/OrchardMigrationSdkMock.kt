package co.electriccoin.zcash.ui.common.migration

import cash.z.ecc.android.sdk.MigrationProgress
import cash.z.ecc.android.sdk.MigrationSchedule
import cash.z.ecc.android.sdk.MigrationState
import cash.z.ecc.android.sdk.NetworkPrivacyOptions
import cash.z.ecc.android.sdk.NoteSplitProposal
import cash.z.ecc.android.sdk.OrchardMigrationSdk
import cash.z.ecc.android.sdk.TransferProposal
import cash.z.ecc.android.sdk.TransferResult
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.BuildConfig
import co.electriccoin.zcash.ui.common.model.migration.MigrationPlan
import co.electriccoin.zcash.ui.common.model.migration.MigrationTransferStatus
import co.electriccoin.zcash.ui.common.provider.MigrationSyncResumeAtStorageProvider
import co.electriccoin.zcash.ui.common.repository.MigrationPlanRepository
import co.electriccoin.zcash.ui.common.repository.MockOrchardBalanceRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlin.time.toKotlinInstant

/**
 * Mock implementation of [OrchardMigrationSdk] for the PoC branch.
 *
 * State is persisted via [MigrationPlanRepository] so WorkManager Workers survive process death.
 * The Orchard balance itself is faked via [MockOrchardBalanceRepository] (independent of the real
 * wallet balance) so it can actually be depleted as mocked transfers execute.
 * Replace with the real Rust-bridge implementation once the SDK is ready.
 */
class OrchardMigrationSdkMock(
    private val mockBalanceRepository: MockOrchardBalanceRepository,
    private val repository: MigrationPlanRepository,
    private val syncResumeAtStorageProvider: MigrationSyncResumeAtStorageProvider,
) : OrchardMigrationSdk {

    // Debug-only simulated RequiresAttention(InvalidTransfer) — see simulateInvalidTransfer().
    private val simulatedInvalidTransfers = MutableStateFlow(false)

    // ── State ────────────────────────────────────────────────────────────────

    override fun getMigrationState(): MigrationState {
        val plan = runCatching { runBlocking { repository.load() } }.getOrNull()
        return when {
            plan == null -> MigrationState.NotStarted
            plan.isComplete -> MigrationState.Complete
            else -> MigrationState.InProgress(buildProgress(plan))
        }
    }

    override fun getMigrationProgress(): MigrationProgress? {
        val plan = runCatching { runBlocking { repository.load() } }.getOrNull() ?: return null
        return buildProgress(plan)
    }

    private fun buildProgress(plan: MigrationPlan): MigrationProgress {
        val remaining = plan.transfers
            .filter { it.status != MigrationTransferStatus.SENT }
            .sumOf { it.amountZatoshi }
        return MigrationProgress(
            completedTransfers = plan.completedCount,
            totalTransfers = plan.totalCount,
            remainingOrchardZatoshi = remaining,
            nextTransferReadyAtHeight = plan.nextPending?.scheduledAtEpochSeconds,
        )
    }

    // ── Note splitting ───────────────────────────────────────────────────────

    override fun isNoteSplitNeeded(): Boolean = true

    override suspend fun prepareNoteSplit(): NoteSplitProposal {
        val total = orchardBalance()
        return NoteSplitProposal(
            outputNotes = splitEvenly(total, count = 4),
            fee = 1_000L,
        )
    }

    override suspend fun submitNoteSplit(proposal: NoteSplitProposal): TransferResult {
        Twig.debug { "OrchardMigrationSdkMock: mock note split submitted" }
        // Broadcast itself is fast — the txId is known right away, matching the real SDK (state
        // transitions to SplitPendingConfirmation once broadcast, not once confirmed). The app-level
        // "wait for confirmation" delay that keeps IN_PROGRESS visible lives in the VM, not here.
        delay(NOTE_SPLIT_BROADCAST_DELAY)
        mockBalanceRepository.decrease(proposal.fee)
        return TransferResult.Success("mock_split_${System.currentTimeMillis()}")
    }

    // ── Migration proposal ───────────────────────────────────────────────────

    override suspend fun proposeMigrationTransfers(includeResidual: Boolean): MigrationSchedule {
        // Mock does not simulate the residual-note concept — the real bridge's ~0.001-1 ZEC
        // leftover doesn't have a mocked equivalent here, so this flag is currently a no-op.
        val total = orchardBalance()
        val amounts = splitEvenly(total, count = TRANSFER_COUNT)
        val intervalSeconds = if (BuildConfig.DEBUG) DEBUG_INTERVAL_SECONDS else PROD_INTERVAL_SECONDS
        val nowSeconds = Clock.System.now().epochSeconds

        val transfers = amounts.mapIndexed { i, amount ->
            TransferProposal(
                id = "transfer_$i",
                amountZatoshi = amount,
                // Nearest interval boundary (mock proxy for 288-block anchor bucket)
                anchorHeight = (nowSeconds / intervalSeconds) * intervalSeconds,
                // Even the first transfer can't execute right now — broadcasting takes a real
                // anchor/proposal round trip. Each transfer is one interval after the previous,
                // starting one interval from now (e.g. t1=2min, t2=4min, t3=6min in debug).
                nextExecutableAfterHeight = nowSeconds + ((i + 1) * intervalSeconds),
                expiryHeight = nowSeconds + ((i + 2) * intervalSeconds),
            )
        }
        return MigrationSchedule(
            transfers = transfers,
            estimatedDurationHours = ((TRANSFER_COUNT - 1) * intervalSeconds / 3600L).toInt(),
        )
    }

    override suspend fun proposeImmediateMigration(): MigrationSchedule {
        val total = orchardBalance()
        val nowSeconds = Clock.System.now().epochSeconds
        return MigrationSchedule(
            transfers = listOf(
                TransferProposal(
                    id = "transfer_immediate",
                    amountZatoshi = total,
                    anchorHeight = nowSeconds,
                    nextExecutableAfterHeight = nowSeconds,
                    expiryHeight = nowSeconds + IMMEDIATE_EXPIRY_WINDOW_SECONDS,
                )
            ),
            estimatedDurationHours = 0,
        )
    }

    // signAndStoreMigrationSchedule: SDK perspective (signing). Persistence is handled
    // separately by MigrationSetupVM via MigrationPlanRepository.
    override suspend fun signAndStoreMigrationSchedule(schedule: MigrationSchedule) {
        Twig.debug { "OrchardMigrationSdkMock: schedule signed (${schedule.transfers.size} transfers)" }
    }

    // ── Background execution ─────────────────────────────────────────────────

    override fun isSyncRequiredBeforeNextTransfer(): Boolean = false

    override suspend fun executeNextPendingTransfer(options: NetworkPrivacyOptions): TransferResult? {
        val plan = repository.load() ?: return null
        val next = plan.nextPending ?: return null
        // Captured before broadcasting: only an overdue transfer being sent out-of-band (the
        // "send now" resume path) needs the post-broadcast privacy buffer — a transfer executed
        // on its normal schedule was never blocking sync in the first place.
        val wasOverdue = next.scheduledAt <= Clock.System.now()

        Twig.debug { "OrchardMigrationSdkMock: mock-sending transfer ${next.index + 1}/${plan.totalCount} (tor=${options.useTor})" }
        delay(200)

        repository.updateTransfer(next.index, MigrationTransferStatus.SENT)
        mockBalanceRepository.decrease(next.amountZatoshi)
        if (wasOverdue) {
            syncResumeAtStorageProvider.store((Clock.System.now() + privacySyncBufferDuration()).toJavaInstant())
        }
        return TransferResult.Success("mock_tx_${System.currentTimeMillis()}")
    }

    override fun isSyncBlocked(): Flow<Boolean> =
        combine(
            repository.observe(), syncResumeAtStorageProvider.observe(), simulatedInvalidTransfers, tickerFlow(SYNC_BLOCK_TICK)
        ) { plan, resumeAt, invalid, _ ->
            val overdue = plan?.let { !it.isComplete && it.nextPending?.scheduledAt?.let { at -> at <= Clock.System.now() } == true } ?: false
            val bufferActive = resumeAt != null && resumeAt.toKotlinInstant() > Clock.System.now()
            overdue || bufferActive || invalid
        }.distinctUntilChanged()

    override fun privacySyncBufferDuration(): Duration =
        if (BuildConfig.DEBUG) DEBUG_PRIVACY_SYNC_BUFFER else PROD_PRIVACY_SYNC_BUFFER

    // ── On-launch reconciliation ─────────────────────────────────────────────

    override fun hasOverdueTransfers(): Boolean {
        val plan = runCatching { runBlocking { repository.load() } }.getOrNull() ?: return false
        if (plan.isComplete) return false
        val next = plan.nextPending ?: return false
        return next.scheduledAt <= Clock.System.now()
    }

    override suspend fun rescheduleOverdueTransfer(): TransferProposal {
        val plan = repository.load() ?: error("OrchardMigrationSdkMock: no migration plan to reschedule")
        val next = plan.nextPending ?: error("OrchardMigrationSdkMock: no pending transfer to reschedule")
        val intervalSeconds = if (BuildConfig.DEBUG) DEBUG_INTERVAL_SECONDS else PROD_INTERVAL_SECONDS
        val newScheduledAt = Clock.System.now().epochSeconds + intervalSeconds
        repository.rescheduleTransfer(next.index, newScheduledAt)
        return TransferProposal(
            id = "transfer_${next.index}",
            amountZatoshi = next.amountZatoshi,
            anchorHeight = newScheduledAt,
            nextExecutableAfterHeight = newScheduledAt,
            expiryHeight = newScheduledAt + intervalSeconds,
        )
    }

    override fun hasInvalidTransfers(): Boolean = simulatedInvalidTransfers.value

    // ── Invalidity recovery ──────────────────────────────────────────────────

    override suspend fun restartCurrentMigrationStep(includeResidual: Boolean): MigrationSchedule {
        simulatedInvalidTransfers.value = false
        return proposeMigrationTransfers(includeResidual)
    }

    // Debug-only QA hook (see MigrationProgressVM.onSimulateInvalidTransfer) — the real SDK
    // will surface RequiresAttention(InvalidTransfer) on its own once it exists; this mock has
    // no organic way to reach that state otherwise.
    fun simulateInvalidTransfer() {
        simulatedInvalidTransfers.value = true
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    // Time passing alone can flip "overdue"/"buffer elapsed" even with no data change, so
    // isSyncBlocked() needs to re-evaluate periodically, not just when the plan/timestamp change.
    private fun tickerFlow(interval: Duration): Flow<Unit> = flow {
        while (currentCoroutineContext().isActive) {
            emit(Unit)
            delay(interval)
        }
    }

    private suspend fun orchardBalance(): Long = mockBalanceRepository.get()

    private fun splitEvenly(total: Long, count: Int): List<Long> {
        if (total <= 0L || count <= 0) return List(count) { 0L }
        val base = total / count
        val remainder = total % count
        val amounts = MutableList(count) { i -> if (i < remainder) base + 1L else base }
        // Small jitter (±5% of base) so amounts look organic in the UI
        val jitter = (base * 0.05).toLong().coerceAtLeast(1L)
        for (i in 0 until count - 1) {
            val shift = Random.nextLong(-jitter, jitter)
            amounts[i] += shift
            amounts[i + 1] -= shift
        }
        return amounts
    }

    companion object {
        private const val TRANSFER_COUNT = 3

        // Compressed stand-in for the real ~6h anchor-bucket cadence — long enough to actually
        // observe WorkManager executing transfers one at a time in the background (rather than
        // the whole plan finishing before you can navigate to check on it), short enough to not
        // require sitting around for hours during manual testing.
        private const val DEBUG_INTERVAL_SECONDS = 120L
        private const val PROD_INTERVAL_SECONDS = 6 * 3600L
        private const val IMMEDIATE_EXPIRY_WINDOW_SECONDS = 3600L
        private val NOTE_SPLIT_BROADCAST_DELAY = 500.milliseconds

        // Post-broadcast privacy buffer for the "send now" resume path — compressed in debug so
        // the auto-resume is actually observable during manual testing.
        private val DEBUG_PRIVACY_SYNC_BUFFER = 30.seconds
        private val PROD_PRIVACY_SYNC_BUFFER = 10.minutes
        private val SYNC_BLOCK_TICK = 15.seconds
    }
}
