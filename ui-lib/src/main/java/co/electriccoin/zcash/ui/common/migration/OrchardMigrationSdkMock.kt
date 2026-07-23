package co.electriccoin.zcash.ui.common.migration

import cash.z.ecc.android.sdk.KeystoneBatchDecodeResult
import cash.z.ecc.android.sdk.KeystoneBatchSignedPczts
import cash.z.ecc.android.sdk.MigrationProgress
import cash.z.ecc.android.sdk.MigrationSchedule
import cash.z.ecc.android.sdk.MigrationState
import cash.z.ecc.android.sdk.MigrationTransferState
import cash.z.ecc.android.sdk.MigrationTransferStates
import cash.z.ecc.android.sdk.NetworkPrivacyOptions
import cash.z.ecc.android.sdk.NoteSplitProposal
import cash.z.ecc.android.sdk.OrchardMigrationSdk
import cash.z.ecc.android.sdk.TransferProposal
import cash.z.ecc.android.sdk.TransferResult
import cash.z.ecc.android.sdk.ext.ZcashSdk
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
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
import kotlin.math.ln
import kotlin.math.roundToLong
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
 * Mock implementation of [OrchardMigrationSdk], kept for reference/testing but no longer bound in
 * Koin — [co.electriccoin.zcash.ui.common.usecase.GetOrchardMigrationSdkUseCase] resolves the real
 * Rust-bridge implementation ([cash.z.ecc.android.sdk.OrchardMigrationSdk.new]) instead.
 *
 * State is persisted via [MigrationPlanRepository] so WorkManager Workers survive process death.
 * The Orchard balance itself is faked via [MockOrchardBalanceRepository] (independent of the real
 * wallet balance) so it can actually be depleted as mocked transfers execute.
 */
class OrchardMigrationSdkMock(
    private val mockBalanceRepository: MockOrchardBalanceRepository,
    private val repository: MigrationPlanRepository,
    private val syncResumeAtStorageProvider: MigrationSyncResumeAtStorageProvider,
) : OrchardMigrationSdk {

    // Debug-only simulated RequiresAttention(InvalidTransfer) — see simulateInvalidTransfer().
    private val simulatedInvalidTransfers = MutableStateFlow(false)

    // ── State ────────────────────────────────────────────────────────────────

    override suspend fun getMigrationState(): MigrationState {
        val plan = runCatching { repository.load() }.getOrNull()
        return when {
            plan == null -> MigrationState.NotStarted
            plan.isComplete -> MigrationState.Complete
            else -> MigrationState.InProgress(buildProgress(plan))
        }
    }

    override suspend fun getMigrationProgress(): MigrationProgress? {
        val plan = runCatching { repository.load() }.getOrNull() ?: return null
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

    override suspend fun isNoteSplitNeeded(): Boolean = true

    override suspend fun estimateMigrationRunCount(): Int? = 1

    override suspend fun prepareNoteSplit(): NoteSplitProposal {
        val total = orchardBalance()
        // Mirrors Rust's converge_denomination_plan, minus the fixed-point convergence loop (this
        // path is currently unreachable from the UI — the standalone Note Split screen was
        // removed once split+schedule+presign merged into one Confirm Transfer Plan step — so a
        // flat fee estimate is an adequate approximation rather than a real convergence).
        val plan = planDenominations(total, prepFeeZatoshi = NOTE_SPLIT_PREP_FEE_ZATOSHI)
        return NoteSplitProposal(
            outputNotes = plan.migrationOutputs,
            fee = NOTE_SPLIT_PREP_FEE_ZATOSHI,
        )
    }

    override suspend fun submitNoteSplit(proposal: NoteSplitProposal, usk: UnifiedSpendingKey): TransferResult {
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
        val total = orchardBalance()
        // No fee reservation here (unlike prepareNoteSplit): this is only correct when the
        // spendable balance already consists of the split's self-funding notes, each already
        // carrying its own transfer-fee buffer — decomposing with a zero prep-fee reproduces those
        // exact notes back as crossing values. Call this only when no split is needed; whenever a
        // split is about to run or just ran, use proposeMigrationTransfersFromSplit instead (see
        // its doc comment / the Rust bridge's propose_migration_transfers_from_split for why: this
        // and prepareNoteSplit compute independent guesses over the same balance that are not
        // guaranteed to agree).
        val plan = planDenominations(total, prepFeeZatoshi = 0L)
        val crossingValues = plan.crossingValues.toMutableList()
        if (includeResidual) {
            plan.orchardChange
                ?.takeIf { it >= RESIDUAL_MIGRATION_MIN_ZATOSHI }
                ?.let { residual ->
                    // The residual has no fee pre-reserved by the denomination plan — net out the
                    // transfer-fee buffer so the scheduled crossing value is what actually lands
                    // in Ironwood (mirrors Rust's propose_migration_transfers).
                    crossingValues += residual - TRANSFER_FEE_BUFFER_ZATOSHI
                }
        }
        return buildMockSchedule(crossingValues)
    }

    override suspend fun proposeMigrationTransfersFromSplit(splitProposal: NoteSplitProposal): MigrationSchedule {
        // Crossing values come straight from the split's own output plan — not an independently
        // recomputed denomination guess (mirrors the Rust bridge exactly).
        val crossingValues = splitProposal.outputNotes.map { it - TRANSFER_FEE_BUFFER_ZATOSHI }
        return buildMockSchedule(crossingValues)
    }

    private fun buildMockSchedule(crossingValues: List<Long>): MigrationSchedule {
        val targetCadenceSeconds = if (BuildConfig.DEBUG) DEBUG_TARGET_CADENCE_SECONDS else PROD_TARGET_CADENCE_SECONDS
        val maxCadenceSeconds = if (BuildConfig.DEBUG) DEBUG_MAX_CADENCE_SECONDS else PROD_MAX_CADENCE_SECONDS
        val nowSeconds = Clock.System.now().epochSeconds

        // Even the first transfer can't execute right now — broadcasting takes a real
        // anchor/proposal round trip. Every later transfer's gap is independently sampled from an
        // exponential distribution around targetCadenceSeconds (mirrors Rust's
        // sample_cadence_blocks / build_schedule), not a fixed offset, so gaps aren't a
        // predictable uniform pattern.
        var next = nowSeconds + targetCadenceSeconds
        val transfers = crossingValues.mapIndexed { i, amount ->
            if (i > 0) next += sampleCadenceSeconds(targetCadenceSeconds, maxCadenceSeconds)
            TransferProposal(
                id = "transfer_$i",
                amountZatoshi = amount,
                anchorHeight = nowSeconds,
                nextExecutableAfterHeight = next,
                expiryHeight = next + targetCadenceSeconds,
            )
        }
        val firstAt = transfers.minOfOrNull { it.nextExecutableAfterHeight } ?: nowSeconds
        val lastAt = transfers.maxOfOrNull { it.nextExecutableAfterHeight } ?: nowSeconds
        return MigrationSchedule(
            transfers = transfers,
            estimatedDurationHours = ceilDiv(lastAt - firstAt, 3600L).toInt(),
        )
    }

    // proposeImmediateMigration() now returns an ordinary send-max Proposal (bypassing the
    // migration engine entirely), which this mock has no real wallet/note store to build one
    // from — same "not mocked" treatment as the external-signer path below.
    override suspend fun proposeImmediateMigration(): Proposal =
        error("OrchardMigrationSdkMock: proposeImmediateMigration (send-max) is not mocked")

    // signAndStoreMigrationSchedule: SDK perspective (signing). Persistence is handled
    // separately by MigrationSetupVM via MigrationPlanRepository.
    override suspend fun signAndStoreMigrationSchedule(schedule: MigrationSchedule, usk: UnifiedSpendingKey) {
        Twig.debug { "OrchardMigrationSdkMock: schedule signed (${schedule.transfers.size} transfers)" }
    }

    // ── External signer (Keystone hardware wallet) ────────────────────────────
    // Never wired to a real Keystone flow (this mock isn't bound in Koin) — unsupported stubs
    // only to satisfy the interface.

    override suspend fun createUnsignedNoteSplitPczt(): ByteArray =
        error("OrchardMigrationSdkMock: external-signer path is not mocked")

    override suspend fun storeSignedNoteSplitPczt(signedPczt: ByteArray, options: NetworkPrivacyOptions): TransferResult =
        error("OrchardMigrationSdkMock: external-signer path is not mocked")

    override suspend fun createUnsignedTransferPczts(schedule: MigrationSchedule): List<Pair<String, ByteArray>> =
        error("OrchardMigrationSdkMock: external-signer path is not mocked")

    override suspend fun storeSignedSchedulePczts(signed: List<Pair<String, ByteArray>>) {
        error("OrchardMigrationSdkMock: external-signer path is not mocked")
    }

    override suspend fun buildKeystoneSignBatchQrParts(
        requestId: ByteArray,
        splitUnsignedPczt: ByteArray?,
        transferUnsignedPczts: List<ByteArray>,
        maxFragmentLen: Int
    ): List<String> = error("OrchardMigrationSdkMock: external-signer path is not mocked")

    override suspend fun resetKeystoneSignBatchDecoder() {
        error("OrchardMigrationSdkMock: external-signer path is not mocked")
    }

    override suspend fun decodeKeystoneSignBatchPart(part: String, expectedRequestId: ByteArray): KeystoneBatchDecodeResult =
        error("OrchardMigrationSdkMock: external-signer path is not mocked")

    override suspend fun applyKeystoneBatchSignatures(
        splitUnsignedPczt: ByteArray?,
        transferUnsignedPczts: List<ByteArray>,
        batchSignResponse: ByteArray
    ): KeystoneBatchSignedPczts = error("OrchardMigrationSdkMock: external-signer path is not mocked")

    // ── Background execution ─────────────────────────────────────────────────

    override suspend fun finalizeReadyTransfers(): Int = 0

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

    override suspend fun hasOverdueTransfers(): Boolean {
        val plan = runCatching { repository.load() }.getOrNull() ?: return false
        if (plan.isComplete) return false
        val next = plan.nextPending ?: return false
        return next.scheduledAt <= Clock.System.now()
    }

    override suspend fun rescheduleOverdueTransfer(): TransferProposal {
        val plan = repository.load() ?: error("OrchardMigrationSdkMock: no migration plan to reschedule")
        val next = plan.nextPending ?: error("OrchardMigrationSdkMock: no pending transfer to reschedule")
        val intervalSeconds = if (BuildConfig.DEBUG) DEBUG_TARGET_CADENCE_SECONDS else PROD_TARGET_CADENCE_SECONDS
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

    // Real detection condition per the interface doc: "spent note or expired anchor." The
    // expired-anchor half is organically derivable client-side (compare against now); the
    // spent-note half needs real chain state we don't have in a mock, so it stays a debug-only
    // simulated flag (see simulateInvalidTransfer()).
    override suspend fun hasInvalidTransfers(): Boolean {
        if (simulatedInvalidTransfers.value) return true
        val plan = runCatching { repository.load() }.getOrNull() ?: return false
        if (plan.isComplete) return false
        val next = plan.nextPending ?: return false
        return next.expiryAt <= Clock.System.now()
    }

    // This mock has no real block heights — its "heights" are epoch seconds scaled down by the
    // block interval, so that MigrationProgressVM's shared estimatedSecondsBetweenHeights(tip,
    // scheduled) conversion (which multiplies back up by the same interval) round-trips to
    // approximately the right wall-clock time.
    override suspend fun getMigrationTransferStates(): MigrationTransferStates? {
        val plan = repository.load() ?: return null
        val blockIntervalSeconds = ZcashSdk.BLOCK_INTERVAL_MILLIS / 1000
        return MigrationTransferStates(
            transfers = plan.transfers.map { t ->
                MigrationTransferState(
                    id = t.id,
                    isSent = t.status == MigrationTransferStatus.SENT,
                    scheduledHeight = t.scheduledAtEpochSeconds / blockIntervalSeconds,
                )
            },
            tipHeight = Clock.System.now().epochSeconds / blockIntervalSeconds,
        )
    }

    // ── Invalidity recovery ──────────────────────────────────────────────────

    override suspend fun restartCurrentMigrationStep(includeResidual: Boolean): MigrationSchedule {
        simulatedInvalidTransfers.value = false
        return proposeMigrationTransfers(includeResidual)
    }

    // ── Dust locking ─────────────────────────────────────────────────────────

    // Matches the real Rust-layer MIGRATION_DUST_THRESHOLD_ZATOSHI constant this mock stands in for.
    override suspend fun migrationDustThresholdZatoshi(): Long = MIGRATION_DUST_THRESHOLD_ZATOSHI

    // TODO: no-op stub, mirrors OrchardMigrationSdkImpl — no real unspendable-note tracking yet.
    override suspend fun lockRemainingOrchardBalance() = Unit

    // ── Debug ─────────────────────────────────────────────────────────────────

    // No-op: this mock has no persisted migration table to wipe.
    override suspend fun clearMigration() = Unit

    // No-op: this mock has no persisted schedule to rewrite.
    override suspend fun debugRescheduleTransfers() = 0

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

    // Ported from zcash_pool_migration's plan_denominations (denominations.rs): deterministic
    // greedy power-of-ten decomposition into self-funding notes (each worth crossingValue +
    // TRANSFER_FEE_BUFFER_ZATOSHI), capped at MIGRATION_MAX_PREPARED_NOTES_PER_RUN. Whatever can't
    // form a whole note is returned as orchardChange (dust or a genuine opt-in residual) — never
    // folded into the fee.
    private data class DenominationPlan(
        val migrationOutputs: List<Long>,
        val crossingValues: List<Long>,
        val orchardChange: Long?,
    )

    private fun planDenominations(totalInputZatoshi: Long, prepFeeZatoshi: Long): DenominationPlan {
        if (totalInputZatoshi <= prepFeeZatoshi) return DenominationPlan(emptyList(), emptyList(), null)
        var budget = totalInputZatoshi - prepFeeZatoshi

        val migrationOutputs = mutableListOf<Long>()
        val crossingValues = mutableListOf<Long>()
        while (budget >= ZATOSHIS_PER_ZEC + TRANSFER_FEE_BUFFER_ZATOSHI &&
            migrationOutputs.size < MIGRATION_MAX_PREPARED_NOTES_PER_RUN
        ) {
            // Largest power-of-ten ZEC denomination `d` with `d * 10 + buffer <= budget`.
            var d = ZATOSHIS_PER_ZEC
            while (d * 10 + TRANSFER_FEE_BUFFER_ZATOSHI <= budget) d *= 10
            val note = d + TRANSFER_FEE_BUFFER_ZATOSHI
            migrationOutputs += note
            crossingValues += d
            budget -= note
        }
        return DenominationPlan(migrationOutputs, crossingValues, budget.takeIf { it > 0L })
    }

    // Ported from zcash_pool_migration's sample_cadence_blocks (scheduling.rs): inverse-transform
    // sampling from an exponential distribution with the given mean, clamped to [1, maxSeconds] so
    // gaps between a wallet's own transfers aren't a predictable uniform pattern, and never land
    // two transfers on the same second.
    private fun sampleCadenceSeconds(targetSeconds: Long, maxSeconds: Long): Long {
        val u = Random.nextDouble()
        val sample = (-ln(1.0 - u) * targetSeconds).roundToLong()
        return sample.coerceIn(1L, maxSeconds)
    }

    private fun ceilDiv(numerator: Long, denominator: Long): Long =
        if (numerator <= 0L) 0L else (numerator + denominator - 1) / denominator

    companion object {
        // Denomination-planning constants, matching zcash_pool_migration's denominations.rs exactly.
        private const val ZATOSHIS_PER_ZEC = 100_000_000L
        private const val MIGRATION_MAX_PREPARED_NOTES_PER_RUN = 64
        private const val TRANSFER_FEE_BUFFER_ZATOSHI = 20_000L
        private const val RESIDUAL_MIGRATION_MIN_ZATOSHI = 100_000L
        private const val MIGRATION_DUST_THRESHOLD_ZATOSHI = 100_000L
        private const val NOTE_SPLIT_PREP_FEE_ZATOSHI = 10_000L

        // Compressed stand-in for the real ~6h target / ~24h max cadence (TARGET_CADENCE_BLOCKS /
        // MAX_CADENCE_BLOCKS in scheduling.rs, same 4x ratio) — long enough to actually observe
        // WorkManager executing transfers one at a time in the background (rather than the whole
        // plan finishing before you can navigate to check on it), short enough to not require
        // sitting around for hours during manual testing.
        private const val DEBUG_TARGET_CADENCE_SECONDS = 120L
        private const val DEBUG_MAX_CADENCE_SECONDS = 4 * DEBUG_TARGET_CADENCE_SECONDS
        private const val PROD_TARGET_CADENCE_SECONDS = 6 * 3600L
        private const val PROD_MAX_CADENCE_SECONDS = 4 * PROD_TARGET_CADENCE_SECONDS
        private val NOTE_SPLIT_BROADCAST_DELAY = 500.milliseconds

        // Post-broadcast privacy buffer for the "send now" resume path — compressed in debug so
        // the auto-resume is actually observable during manual testing.
        private val DEBUG_PRIVACY_SYNC_BUFFER = 30.seconds
        private val PROD_PRIVACY_SYNC_BUFFER = 10.minutes
        private val SYNC_BLOCK_TICK = 15.seconds
    }
}
