package co.electriccoin.zcash.ui.screen.migration.progress

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.MigrationTransferStates
import cash.z.ecc.android.sdk.NetworkPrivacyOptions
import cash.z.ecc.android.sdk.ext.convertZatoshiToZec
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.migration.BuildConfig
import co.electriccoin.zcash.migration.migrationLog
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.model.LceState
import co.electriccoin.zcash.ui.common.model.guardLoading
import co.electriccoin.zcash.ui.common.model.migration.LiveMigrationPreparation
import co.electriccoin.zcash.ui.common.model.migration.LiveMigrationSnapshot
import co.electriccoin.zcash.ui.common.model.migration.LiveMigrationTransfer
import co.electriccoin.zcash.ui.common.model.migration.MigrationTransferAction
import co.electriccoin.zcash.ui.common.model.migration.MigrationTransferBlocker
import co.electriccoin.zcash.ui.common.model.migration.formatMigrationDuration
import co.electriccoin.zcash.ui.common.model.migration.toSnapshot
import co.electriccoin.zcash.ui.common.model.mutableLce
import co.electriccoin.zcash.ui.common.model.stateIn
import co.electriccoin.zcash.ui.common.model.toStorageKeyId
import co.electriccoin.zcash.ui.common.model.withLce
import co.electriccoin.zcash.ui.common.provider.IsMigrationTorEnabledStorageProvider
import co.electriccoin.zcash.ui.common.provider.LastNetworkActivityStorageProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.repository.ExchangeRateRepository
import co.electriccoin.zcash.ui.common.usecase.ErrorMapperUseCase
import co.electriccoin.zcash.ui.common.usecase.GetMigrationSnapshotUseCase
import co.electriccoin.zcash.ui.common.usecase.GetOrchardMigrationSdkUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.common.wallet.ExchangeRateState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.stringResByDynamicCurrencyNumber
import co.electriccoin.zcash.ui.screen.migration.sending.MigrationSendingArgs
import co.electriccoin.zcash.work.MigrationScheduler
import co.electriccoin.zcash.work.SYNC_TIMEOUT
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.MathContext
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class MigrationProgressVM(
    private val getOrchardMigrationSdk: GetOrchardMigrationSdkUseCase,
    private val getSelectedWalletAccount: GetSelectedWalletAccountUseCase,
    private val navigationRouter: NavigationRouter,
    private val exchangeRateRepository: ExchangeRateRepository,
    private val errorStateMapper: ErrorMapperUseCase,
    private val synchronizerProvider: SynchronizerProvider,
    private val lastNetworkActivity: LastNetworkActivityStorageProvider,
    private val isMigrationTorEnabledStorageProvider: IsMigrationTorEnabledStorageProvider,
    private val context: Context,
) : ViewModel() {
    private val sendLce = mutableLce<Unit>()

    val state: StateFlow<LceState<MigrationProgressState>> =
        combine(
            exchangeRateRepository.state,
            liveTransferStatesFlow(),
        ) { rate, liveStates ->
            // Everything on this screen derives LIVE from the engine's persisted states — no plan
            // cache to diverge, and no app-side "overdue"/countdown: each row renders purely from
            // the engine's per-transaction status (decision with Dominik 2026-07-31). The measured
            // block rate is still used for the rough total-duration estimate in the header only.
            val secondsPerBlock = getOrchardMigrationSdk()?.estimatedSecondsPerBlock() ?: 75L
            val est = getOrchardMigrationSdk()?.estimatedChainTip() ?: -1L
            liveStates
                ?.toSnapshot(
                    estimatedTip = if (est >= 0) est else liveStates.tipHeight,
                    secondsPerBlock = secondsPerBlock,
                    nowEpochSeconds = Clock.System.now().epochSeconds,
                )?.let { createState(it, rate) }
        }.withLce(sendLce, errorStateMapper::mapToState)
            .stateIn(this)

    init {
        // Issue 3b: drive migration forward WHILE the progress screen is foregrounded.
        //
        // Root cause of the stall: on this screen the app is foreground and the main synchronizer
        // follows the chain tip continuously, so Lane B's background preflight sees
        // synchronizerSyncing=true and DEFER_OVERLAPs forever — nothing ever broadcasts while the
        // user watches (every successful E2E previously required backgrounding the app to open a
        // Lane B quiet window). This foreground pass opens that window itself, PRIVACY-PRESERVED:
        // it pauses the main synchronizer, waits out the privacy quiet gap, then broadcasts through
        // the EXACT same pipeline Lane B/Sending use (executeNextPendingTransfer) — never a raw
        // send, and never while a sync source is live. The side effect lives here in init{} (not in
        // the state combine) so it runs once per VM instance rather than re-subscribing.
        foregroundBroadcastLoop()
    }

    // MigrationPlanRepository's per-transfer status/scheduledAt is a display cache, written once
    // at propose/commit time. Polling the SDK's own persisted state directly keeps the displayed
    // schedule true to the engine — the single source of truth for the plan — regardless of what
    // the cache last recorded.
    private fun liveTransferStatesFlow(): Flow<MigrationTransferStates?> =
        flow {
            while (true) {
                val sdk = getOrchardMigrationSdk()
                emit(sdk?.getMigrationTransferStates())
                delay(OVERDUE_RECHECK_INTERVAL)
            }
        }

    /**
     * Issue 3b — the foreground broadcast pass. Periodically, while this VM is alive (i.e. the
     * progress screen is on top), checks whether a transfer is genuinely due AND proved; if so,
     * acquires a privacy-safe broadcast window and broadcasts it via the same SDK pipeline the
     * background Lane B uses. Runs on the VM scope, so it is cancelled automatically when the
     * screen leaves.
     */
    private fun foregroundBroadcastLoop() =
        viewModelScope.launch {
            while (true) {
                runCatching { attemptForegroundBroadcast() }
                    .onFailure {
                        // Swallowing a CancellationException would fight structured concurrency —
                        // the VM scope is going away (navigation), the loop must die with it
                        // (observed live 2026-07-30: navigation churn logged these as "transient"
                        // failures and the pass never completed its quiet-gap wait).
                        if (it is kotlinx.coroutines.CancellationException) throw it
                        migrationLog("ProgressBroadcast: pass failed (transient) — retrying next tick", it)
                    }
                delay(FOREGROUND_BROADCAST_INTERVAL)
            }
        }

    /**
     * One foreground broadcast attempt, privacy-preserved.
     *
     * 1. Only proceeds when the engine holds a PROVED, unsent transaction whose scheduledHeight has
     *    been reached at the SCANNED tip (a broadcast that can actually happen). An unproven due
     *    transfer is left to Lane A's sync to prove — never force-broadcast here.
     * 2. Respects the SDK's own post-broadcast privacy gate (isSyncBlocked): if active, defers.
     * 3. PAUSES the main synchronizer so no sync source is live, waits out the privacy quiet gap
     *    from the last network activity, then broadcasts through executeNextPendingTransfer — the
     *    identical call Lane B and the Sending screen use. After a successful overdue broadcast the
     *    SDK itself sets the post-broadcast resume-at buffer, which keeps the main sync paused via
     *    isSyncBlocked; we still resume() so the SDK-owned gate — not this manual pause — governs
     *    sync from here on.
     */
    private suspend fun attemptForegroundBroadcast() {
        val sdk = getOrchardMigrationSdk() ?: return
        val states = sdk.getMigrationTransferStates() ?: return
        // Only bother when the ENGINE would actually serve a broadcast: a proved, unsent,
        // non-stuck transaction due at the scanned tip (executeNextPendingTransfer re-verifies
        // and picks the exact transaction itself — decision vs action).
        if (!co.electriccoin.zcash.work
                .broadcastDueByEstimate(states, states.tipHeight)
        ) {
            return
        }
        if (sdk.isSyncBlocked().first()) {
            migrationLog("ProgressBroadcast: privacy gate active (isSyncBlocked) — deferring foreground broadcast.")
            return
        }
        val synchronizer = synchronizerProvider.getSynchronizerOrNull()
        // Pause the continuously-syncing foreground synchronizer so the broadcast never overlaps a
        // live sync (privacy). Cast mirrors ResetZashiUseCase — the runtime instance is always a
        // CloseableSynchronizer; a null/incompatible synchronizer simply skips this pass.
        val closeable =
            synchronizer as? cash.z.ecc.android.sdk.CloseableSynchronizer ?: run {
                migrationLog("ProgressBroadcast: no pausable synchronizer — skipping foreground broadcast.")
                return
            }
        closeable.pause()
        // Stamp "network activity" at the moment of pause so the quiet gap below is measured from
        // when THIS sync stopped — not from the last SYNCED transition. In the exact state this
        // path targets (the foreground synchronizer catching up continuously and never reaching
        // SYNCED), lastNetworkActivity is stamped only on SYNCED, so it would be stale and the gap
        // would collapse to ~0 → an immediate broadcast right after an ASYNC pause() whose
        // stopPolling() may still be in flight, i.e. sync traffic still adjacent to the broadcast.
        // Stamping here forces the full privacy buffer to elapse after the sync actually stopped,
        // covering the async stop and giving real decorrelation.
        lastNetworkActivity.stampNow()
        migrationLog("ProgressBroadcast: paused foreground sync to open a broadcast window.")
        try {
            // Wait out the privacy quiet gap since the pause stamp above (same buffer Lane B's
            // preflight enforces) so an observer can't correlate the just-stopped sync with the
            // broadcast. The pause above already removed the live-sync source; this covers the gap.
            val gap = quietGapRemaining(sdk.privacySyncBufferDuration())
            if (gap.isPositive()) {
                migrationLog("ProgressBroadcast: waiting privacy quiet gap $gap before broadcast.")
                delay(gap)
            }
            val useTor = isMigrationTorEnabledStorageProvider.get()
            val outcome = sdk.executeNextPendingTransfer(NetworkPrivacyOptions(useTor = useTor), useEstimatedTip = false)
            migrationLog("ProgressBroadcast: foreground broadcast outcome=$outcome")
            lastNetworkActivity.stampNow()
        } finally {
            // Hand sync governance back to the SDK-owned isSyncBlocked gate (which, after a
            // successful overdue broadcast, keeps sync paused for the post-broadcast buffer).
            closeable.resume()
            migrationLog("ProgressBroadcast: resumed foreground sync (SDK gate now governs).")
        }
    }

    private suspend fun quietGapRemaining(privacyBuffer: kotlin.time.Duration): kotlin.time.Duration {
        val last = lastNetworkActivity.get() ?: return kotlin.time.Duration.ZERO
        val elapsed = (Clock.System.now().epochSeconds - last.epochSecond).seconds
        val remaining = privacyBuffer - elapsed
        return if (remaining.isPositive()) remaining else kotlin.time.Duration.ZERO
    }

    fun navigateBack() = navigationRouter.back()

    private fun createState(
        snapshot: LiveMigrationSnapshot,
        exchangeRateState: ExchangeRateState,
    ): MigrationProgressState {
        val now = Clock.System.now()
        // Rough total-duration estimate for the header only (first→last scheduled moment across
        // preparations AND transfers) — a "the whole thing takes about X" hint, never a per-row
        // deadline.
        val allScheduled = (snapshot.transfers.map { it.scheduledAt } + snapshot.preparations.map { it.scheduledAt })
        val span =
            ((allScheduled.maxOrNull() ?: now) - (allScheduled.minOrNull() ?: now)).inWholeSeconds
        val subtitle =
            if (snapshot.isComplete) {
                "All ${snapshot.totalCount} transfers are complete."
            } else {
                "Your balance splits into ${snapshot.totalCount} transfers over " +
                    "${formatMigrationDuration(span)}. There are " +
                    "${snapshot.totalCount - snapshot.completedCount} remaining transfers."
            }

        val totalZatoshi = snapshot.transfers.sumOf { it.amountZatoshi }
        return MigrationProgressState(
            title = stringRes("Migration Progress"),
            subtitle = stringRes(subtitle),
            totalAmount = stringRes(Zatoshi(totalZatoshi)),
            totalFiatAmount = fiatAmount(Zatoshi(totalZatoshi), exchangeRateState),
            preparations =
                snapshot.preparations.mapIndexed { i, p ->
                    MigrationProgressPreparationState(
                        number = i + 1,
                        statusLabel = preparationStatusLabel(p),
                        isSent = p.isSent,
                        // BuildConfig.DEBUG read inline (matching the codebase's other VMs) rather than
                        // injected — a `Boolean` constructor param breaks Koin's `viewModelOf` reflective
                        // resolution (NoDefinitionFoundException at screen open).
                        syncLabel = if (BuildConfig.DEBUG) preparationSyncLabel(p, now) else null,
                    )
                },
            transfers =
                snapshot.transfers.map { t ->
                    MigrationProgressTransferState(
                        index = t.index + 1,
                        amount = stringRes(Zatoshi(t.amountZatoshi)),
                        fiatAmount = fiatAmount(Zatoshi(t.amountZatoshi), exchangeRateState),
                        statusLabel = transferLabel(t),
                        // Attention paint (orange) ONLY for genuine, cannot-heal-on-its-own states —
                        // never for a merely-late-but-healthy transfer (the old "overdue" false
                        // alarm). Expired and the synthetic unprovable-anchor are the only two.
                        isAttention =
                            t.blocker == MigrationTransferBlocker.UNPROVABLE_ANCHOR ||
                                t.blocker == MigrationTransferBlocker.EXPIRED,
                        isSent = t.isSent,
                        syncLabel = if (BuildConfig.DEBUG) transferSyncLabel(t, now) else null,
                    )
                },
            isComplete = snapshot.isComplete,
            onBack = ::onBack,
            onDone = if (snapshot.isComplete) ::onDone else null,
        )
    }

    private fun fiatAmount(zatoshi: Zatoshi, exchangeRateState: ExchangeRateState): StringResource? {
        val data = exchangeRateState as? ExchangeRateState.Data ?: return null
        val conversion = data.currencyConversion ?: return null
        return stringResByDynamicCurrencyNumber(
            amount =
                zatoshi
                    .convertZatoshiToZec()
                    .multiply(BigDecimal(conversion.priceOfZec), MathContext.DECIMAL128),
            ticker = data.expectedCurrency.symbol,
        )
    }

    private fun onBack() = sendLce.guardLoading { navigationRouter.back() }

    // Privacy buffer bookkeeping (keeping sync paused post-broadcast) is entirely SDK-owned — the
    // SDK notices this transfer was overdue and sets it internally. The actual broadcast, its
    // failure/retry sheet, and re-arming the next window all live on the Sending screen now
    // (see MigrationSendingVM), reused instead of duplicated here.
    private fun onSendNow() = navigationRouter.forward(MigrationSendingArgs)

    // "Reschedule" no longer mutates the plan — a missed-but-unexpired transfer needs NO plan
    // change by design (ZIP 374: the signature does not cover the anchor, so it proves late
    // against its committed boundary and broadcasts late; the engine is the single source of
    private fun onDone() = navigationRouter.backToRoot()

    companion object {
        private val OVERDUE_RECHECK_INTERVAL = 15.seconds

        // How often the foreground broadcast pass (Issue 3b) re-checks for a due, proved transfer.
        // Short enough to advance the migration responsively while watched, long enough not to
        // churn; the SDK's own gates make redundant passes cheap no-ops.
        internal val FOREGROUND_BROADCAST_INTERVAL = 20.seconds
    }
}

/**
 * Status label for a crossing transfer row, rendered PURELY from the engine's per-transaction
 * status (`ready`/`action`/`blocker` from `transaction_statuses`) — NO wall-clock, NO "overdue",
 * NO countdown. The engine has no notion of "overdue": a proved transfer waiting for the engine
 * to reach its broadcast (proving is prioritised) is a normal state, not a failure. Showing a
 * projected countdown that we then don't strictly honour — and painting late-but-healthy rows
 * "Overdue" — made correct engine execution look broken (decision with Dominik 2026-07-31), so
 * both are gone. Every branch maps 1:1 onto `state.rs::transaction_statuses`.
 *
 * Top-level and internal for unit-testability without Android or Koin.
 */
internal fun transferLabel(t: LiveMigrationTransfer): StringResource =
    when {
        t.isSent && t.minedHeight != null -> stringRes("Confirmed")
        t.isSent -> stringRes("Sent")
        t.blocker == MigrationTransferBlocker.EXPIRED -> stringRes("Expired")
        t.blocker == MigrationTransferBlocker.UNPROVABLE_ANCHOR -> stringRes("Needs reschedule")
        t.blocker == MigrationTransferBlocker.SIGNATURE -> stringRes("Awaiting signature")
        t.blocker == MigrationTransferBlocker.DEPENDENCIES -> stringRes("Waiting for note split")
        t.blocker == MigrationTransferBlocker.ANCHOR_BOUNDARY -> stringRes("Waiting for anchor window")
        t.blocker == MigrationTransferBlocker.SCHEDULE -> stringRes("Scheduled")
        t.action == MigrationTransferAction.PROVE -> stringRes("Preparing")
        t.action == MigrationTransferAction.BROADCAST -> stringRes("Sending soon")
        else -> stringRes("Waiting")
    }

/**
 * Status label for a preparation row — same pure-status mapping as [transferLabel]. Preparations
 * are internal note-split plumbing, so the copy is deliberately plain ("Preparing" / "Sending
 * soon" / "Waiting" / "Sent"). No wall-clock, no overdue. Top-level and internal for testability.
 */
internal fun preparationStatusLabel(p: LiveMigrationPreparation): StringResource =
    when {
        p.isSent -> stringRes("Sent")
        p.blocker == MigrationTransferBlocker.SIGNATURE -> stringRes("Awaiting signature")
        p.blocker == MigrationTransferBlocker.DEPENDENCIES -> stringRes("Waiting for previous split")
        p.action == MigrationTransferAction.PROVE -> stringRes("Preparing")
        p.action == MigrationTransferAction.BROADCAST -> stringRes("Sending soon")
        else -> stringRes("Waiting")
    }

/**
 * DEBUG-only prove-state label for a preparation row, formatted with the same relative formatter
 * as [preparationStatusLabel] so "~X min" / pending text look identical. Returns "proved" when
 * the preparation already has a proof, otherwise a relative scheduled time or "pending".
 * Top-level and internal for unit-testability.
 */
internal fun preparationSyncLabel(p: LiveMigrationPreparation, now: Instant): StringResource {
    if (p.isProved) return stringRes("proved")
    val scheduledAt = p.scheduledAt
    return when {
        scheduledAt <= now -> {
            stringRes("pending")
        }

        else -> {
            val secondsLeft = (scheduledAt - now).inWholeSeconds
            stringRes(formatMigrationDuration(secondsLeft))
        }
    }
}

/**
 * DEBUG-only prove-state label for a transfer row, mirroring [preparationSyncLabel]: returns
 * "proved" when the transfer already has a proof, otherwise a relative scheduled time or "pending".
 * Top-level and internal for unit-testability.
 */
internal fun transferSyncLabel(t: LiveMigrationTransfer, now: Instant): StringResource {
    if (t.isProved) return stringRes("proved")
    val scheduledAt = t.scheduledAt
    return when {
        scheduledAt <= now -> {
            stringRes("pending")
        }

        else -> {
            val secondsLeft = (scheduledAt - now).inWholeSeconds
            stringRes(formatMigrationDuration(secondsLeft))
        }
    }
}
