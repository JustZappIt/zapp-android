package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.AttentionReason
import cash.z.ecc.android.sdk.MigrationState
import cash.z.ecc.android.sdk.MigrationTransferStates
import cash.z.ecc.android.sdk.OrchardMigrationSdk
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.migration.MigrationHomeMessageSource
import co.electriccoin.zcash.ui.common.model.migration.MIGRATION_DUST_THRESHOLD_ZATOSHI
import co.electriccoin.zcash.ui.common.model.migration.MIGRATION_RESIDUAL_MIN_ZATOSHI
import co.electriccoin.zcash.ui.common.model.migration.MigrationAttentionKind
import co.electriccoin.zcash.ui.common.model.migration.MigrationPlan
import co.electriccoin.zcash.ui.common.model.migration.affectedTransferIndices
import co.electriccoin.zcash.ui.common.model.migration.toMigrationRangeText
import co.electriccoin.zcash.ui.common.model.migration.toUiKind
import co.electriccoin.zcash.ui.common.provider.HasSeenMigrationCompleteStorageProvider
import co.electriccoin.zcash.ui.common.provider.IsBackgroundExecutionAvailableProvider
import co.electriccoin.zcash.ui.common.repository.MigrationHomeMessage
import co.electriccoin.zcash.ui.common.repository.MigrationHomeMessageData
import co.electriccoin.zcash.ui.common.repository.MigrationPlanRepository
import co.electriccoin.zcash.ui.screen.home.HomeMessageState
import co.electriccoin.zcash.ui.screen.home.migration.MigrationBannerPhase
import co.electriccoin.zcash.ui.screen.home.migration.MigrationMessageState
import co.electriccoin.zcash.ui.screen.migration.complete.MigrationCompleteArgs
import co.electriccoin.zcash.ui.screen.migration.invalid.MigrationTransferInvalidArgs
import co.electriccoin.zcash.ui.screen.migration.progress.MigrationProgressArgs
import co.electriccoin.zcash.ui.screen.migration.setup.MigrationSetupArgs
import co.electriccoin.zcash.ui.screen.migration.transferreview.MigrationTransferReviewArgs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class MigrationHomeMessageSourceImpl(
    private val accountDataSource: AccountDataSource,
    private val migrationPlanRepository: MigrationPlanRepository,
    private val getOrchardMigrationSdk: GetOrchardMigrationSdkUseCase,
    private val getOrchardBalance: GetOrchardBalanceUseCase,
    private val hasSeenMigrationCompleteStorageProvider: HasSeenMigrationCompleteStorageProvider,
    private val isBackgroundExecutionAvailableProvider: IsBackgroundExecutionAvailableProvider,
    private val navigationRouter: NavigationRouter,
) : MigrationHomeMessageSource {
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observe(): Flow<MigrationHomeMessage?> =
        accountDataSource.selectedAccount.flatMapLatest { account ->
            if (account == null) return@flatMapLatest flowOf(null)
            combine(
                migrationPlanRepository.observe(),
                hasSeenMigrationCompleteStorageProvider.observe(),
                observeReadyToSendSignal(),
                // Observed, not a one-shot getOrchardBalance().value read: an IMMEDIATE migration
                // never touches the plan or the SDK's MigrationState (it bypasses the migration
                // engine — a plain send-max sweep), so the balance is the *only* input that can hide
                // the "Migrate required" banner once its Orchard funds are spent. Reading it one-shot
                // left the combine re-firing solely on the 15s readyToSendSignal poll, so the stale
                // Required banner lingered over the whole in-flight transfer even though the balance
                // had already dropped to (and the setup screen showed) 0.
                getOrchardBalance.observe(),
            ) { plan, hasSeenComplete, readyToSendSignal, orchardBalance ->
                val sdk = getOrchardMigrationSdk()
                val sdkState = sdk?.getMigrationState()
                // Only computed when actually needed (RequiresAttention) — an extra
                // getMigrationTransferStates() read on every other state would be wasted work.
                val (attentionKind, attentionRangeText) =
                    (sdkState as? MigrationState.RequiresAttention)?.let { requiresAttention ->
                        attentionInfoFor(sdk, requiresAttention.reason, plan)
                    } ?: (null to null)
                migrationMessageFor(
                    sdkState = sdkState,
                    plan = plan,
                    hasSeenComplete = hasSeenComplete,
                    orchardBalanceZatoshi = orchardBalance?.value ?: 0L,
                    dustThresholdZatoshi = sdk?.migrationDustThresholdZatoshi() ?: MIGRATION_DUST_THRESHOLD_ZATOSHI,
                    isBackgroundExecutionAvailable = readyToSendSignal.isBackgroundExecutionAvailable,
                    hasOverdueTransfers = readyToSendSignal.hasOverdueTransfers,
                    attentionKind = attentionKind,
                    attentionRangeText = attentionRangeText,
                )
            }
        }

    override fun createMessageState(data: MigrationHomeMessage): HomeMessageState {
        data as MigrationHomeMessageData
        val plan = data.plan
        val percent =
            if (plan != null && plan.totalCount > 0) {
                (plan.completedCount * 100) / plan.totalCount
            } else {
                0
            }
        // See MigrationKeystoneRound's kdoc — only ever non-null for a Keystone account's
        // plan, prefixed onto the in-progress subtitle when present.
        val roundPrefix = plan?.keystoneRound?.let { "Round ${it.current} of ${it.total} · " }.orEmpty()
        // Spec §6.2/§6.3 — takes priority over the ordinary phases below: a plan needing
        // re-confirmation is more actionable than its last-known progress/completion state.
        // Title carries the exact required copy ("Update migration plan." / "Transfer 3–5
        // expired.") since that IS the home message per spec, not just a phase label.
        val (phase, title, subtitle) =
            when (data.attentionKind) {
                MigrationAttentionKind.PLAN_UPDATE -> {
                    Triple(MigrationBannerPhase.ATTENTION, "Update migration plan", "Tap to review the details")
                }

                MigrationAttentionKind.TRANSFER_EXPIRED -> {
                    val range = data.attentionRangeText
                    Triple(
                        MigrationBannerPhase.ATTENTION,
                        if (range != null) "Transfer $range expired" else "A transfer expired",
                        "Tap to review the details",
                    )
                }

                null -> {
                    when {
                        data.isComplete -> {
                            Triple(MigrationBannerPhase.COMPLETE, null, "Tap to review the details")
                        }

                        // Spec §6.4: numbered per the due transfer, matching the convention used
                        // elsewhere (e.g. MigrationProgressVM's "Transfer ${completedCount + 1}").
                        data.isReadyToSend -> {
                            Triple(
                                MigrationBannerPhase.READY_TO_SEND,
                                null,
                                "Transfer ${(plan?.completedCount ?: 0) + 1} is ready to send",
                            )
                        }

                        plan == null -> {
                            Triple(MigrationBannerPhase.REQUIRED, null, null)
                        }

                        plan.completedCount == 0 -> {
                            Triple(MigrationBannerPhase.IN_PROGRESS, null, "${roundPrefix}First transfer sending…")
                        }

                        else -> {
                            Triple(
                                MigrationBannerPhase.IN_PROGRESS,
                                null,
                                "$roundPrefix${plan.completedCount} of ${plan.totalCount} transfers done" +
                                    " ~ $percent% complete",
                            )
                        }
                    }
                }
            }
        return MigrationMessageState(
            phase = phase,
            title = title,
            progressLabel = subtitle,
            progressPercent = percent.toFloat(),
            onClick = {
                onMigrationMessageClick(
                    plan = plan,
                    isComplete = data.isComplete,
                    isReadyToSend = data.isReadyToSend,
                    hasAttention = data.attentionKind != null,
                )
            },
            onButtonClick = {
                onMigrationMessageClick(
                    plan = plan,
                    isComplete = data.isComplete,
                    isReadyToSend = data.isReadyToSend,
                    hasAttention = data.attentionKind != null,
                )
            },
        )
    }

    private fun onMigrationMessageClick(
        plan: MigrationPlan?,
        isComplete: Boolean,
        isReadyToSend: Boolean = false,
        hasAttention: Boolean = false,
    ) {
        when {
            // A plan needing re-confirmation (spec §6.2/§6.3) always routes to the Transfer Invalid
            // info screen, regardless of its last-known progress/completion state.
            hasAttention -> navigationRouter.forward(MigrationTransferInvalidArgs)

            // Tapping the widget just opens the celebration screen now — MigrationCompleteVM.onDone()
            // owns the seen-flag decision, since it needs to know whether residual Orchard balance
            // still requires another Keystone round before deciding whether this is truly "seen".
            isComplete -> navigationRouter.forward(MigrationCompleteArgs)

            // Spec §6.4: a distinct, lighter-weight review-and-send path — not the fuller
            // Reschedule/Send-now recovery screen MigrationProgressArgs offers for a genuinely
            // overdue transfer.
            isReadyToSend -> navigationRouter.forward(MigrationTransferReviewArgs)

            plan != null -> navigationRouter.forward(MigrationProgressArgs)

            else -> navigationRouter.forward(MigrationSetupArgs)
        }
    }

    private data class ReadyToSendSignal(
        val isBackgroundExecutionAvailable: Boolean,
        val hasOverdueTransfers: Boolean,
    )

    // Neither signal here is itself observable, so this polls on the same cadence
    // MigrationProgressVM.reallyOverdueFlow() already uses for hasOverdueTransfers() — cheap local
    // checks (no network), just re-evaluated periodically since wall-clock "has this transfer's due
    // time arrived yet" can't otherwise be recomputed reactively.
    private fun observeReadyToSendSignal(): Flow<ReadyToSendSignal> =
        flow {
            while (true) {
                // A failed tick (e.g. "database is locked" outlasting the SDK's own bounded
                // retry while a sync write transaction holds the wallet DB) skips this emission
                // instead of cancelling the whole home-message flow — observed live as a
                // main-thread crash. The next tick re-reads; the signal is periodic anyway.
                runCatching {
                    ReadyToSendSignal(
                        isBackgroundExecutionAvailable = isBackgroundExecutionAvailableProvider.isAvailable(),
                        hasOverdueTransfers = getOrchardMigrationSdk()?.hasOverdueTransfers() ?: false,
                    )
                }.onSuccess { emit(it) }
                delay(READY_TO_SEND_RECHECK_INTERVAL)
            }
        }

    // Spec §6.2/§6.3 home-banner support — see MigrationAttentionKind's doc. Correlates the
    // reason's affected transfer(s) by stable id (never array index, same as MigrationProgressVM's
    // withLiveState) rather than assuming every not-yet-completed cached transfer is affected.
    private suspend fun attentionInfoFor(
        sdk: OrchardMigrationSdk?,
        reason: AttentionReason,
        plan: MigrationPlan?,
    ): Pair<MigrationAttentionKind, String?> {
        val kind = reason.toUiKind()
        if (plan == null) return kind to null
        val liveStates: MigrationTransferStates? = sdk?.getMigrationTransferStates()
        val rangeText =
            reason
                .affectedTransferIndices(plan, liveStates, Clock.System.now().epochSeconds)
                .toMigrationRangeText()
        return kind to rangeText
    }

    private companion object {
        val READY_TO_SEND_RECHECK_INTERVAL = 15.seconds
    }
}

/**
 * The migration home-banner decision, extracted as a pure function so it's directly testable
 * without mocking the whole reactive [MigrationHomeMessageSourceImpl.observe] pipeline.
 *
 * [plan] takes priority over [sdkState] for choosing between the Complete banner and a fresh
 * REQUIRED-equivalent: the SDK's own [MigrationState] stays [MigrationState.Complete] until the
 * *next* round is actually committed (see the engine's `commit_preparation`/`build_preparation_unsigned`
 * docs), so it alone cannot distinguish "round just finished, more residual balance needs another
 * round" from "campaign genuinely done" — both look identical to the SDK. The app-side [plan] can:
 * `MigrationCompleteVM.onDone()` clears it (without setting [hasSeenComplete]) exactly when more
 * rounds are needed, so `plan == null` here means "treat this as if nothing has run yet."
 *
 * [isBackgroundExecutionAvailable] and [hasOverdueTransfers] together drive spec §6.4 "Transfer
 * Ready to Send": a narrower, *earlier* window than the general overdue/missed-transfer state
 * (`MigrationProgressVM`'s `hasOverdue`) — the next pending transfer's scheduled time has arrived,
 * background execution can't run it, but the SDK doesn't (yet) count it as overdue. Both default to
 * "don't show this banner" (available/not-overdue) so existing callers/tests that don't pass them
 * keep behaving exactly as before.
 */
internal fun migrationMessageFor(
    sdkState: MigrationState?,
    plan: MigrationPlan?,
    hasSeenComplete: Boolean,
    orchardBalanceZatoshi: Long,
    dustThresholdZatoshi: Long = MIGRATION_DUST_THRESHOLD_ZATOSHI,
    isBackgroundExecutionAvailable: Boolean = true,
    hasOverdueTransfers: Boolean = false,
    now: Instant = Clock.System.now(),
    // Additive — see MigrationHomeMessageData's doc. Both null unless sdkState is
    // RequiresAttention; callers precompute these (MigrationHomeMessageSourceImpl.attentionInfoFor())
    // so this function stays a pure, synchronous decision with no SDK access of its own.
    attentionKind: MigrationAttentionKind? = null,
    attentionRangeText: String? = null,
): MigrationHomeMessageData? {
    val next = plan?.nextPending
    return when {
        // Spec §6.2/§6.3 — takes priority over InProgress/Complete below: a plan needing
        // re-confirmation is more actionable than its last-known progress snapshot. Falls through
        // to the ordinary branches below when plan is null (a defensive case — RequiresAttention in
        // practice always implies a plan/schedule already existed).
        //
        // SyncRequiredBeforeNext is explicitly excluded here (see MigrationAttentionKind's doc: it is
        // out of scope for toUiKind(), which otherwise collapses it onto TRANSFER_EXPIRED and would
        // surface a wrong "Transfer expired" attention banner). It is a transient "keep syncing"
        // condition — not a user-action-required expiry — so it must not raise an attention banner;
        // falling through lets the ordinary InProgress / no-message branches decide. This mirrors
        // CheckMigrationRecoveryUseCase, which likewise does not route on this reason.
        sdkState is MigrationState.RequiresAttention &&
            sdkState.reason != AttentionReason.SyncRequiredBeforeNext &&
            plan != null -> {
            MigrationHomeMessageData(
                plan,
                attentionKind = attentionKind ?: sdkState.reason.toUiKind(),
                attentionRangeText = attentionRangeText,
            )
        }

        sdkState is MigrationState.InProgress &&
            next != null &&
            !isBackgroundExecutionAvailable &&
            !hasOverdueTransfers &&
            next.scheduledAt <= now -> {
            MigrationHomeMessageData(plan, isReadyToSend = true)
        }

        sdkState is MigrationState.InProgress -> {
            MigrationHomeMessageData(plan)
        }

        // Gated on the migratable minimum, not just the SDK's per-round Complete state — a
        // multi-round Keystone migration reports Complete as soon as the *current* round's
        // transfers are all mined, even with a large residual balance still needing another
        // round. Without this, finishing an earlier round would incorrectly show the one-time
        // completion banner. A sub-migratable residue (below MIGRATION_RESIDUAL_MIN_ZATOSHI, which
        // the engine can't migrate — proposeMigrationTransfers would return NothingToMigrate) still
        // counts as "complete": there is no further round to run, so the completion/residue screen
        // (lock / migrate-anyway) is the correct destination.
        sdkState == MigrationState.Complete &&
            plan != null &&
            !hasSeenComplete &&
            orchardBalanceZatoshi < MIGRATION_RESIDUAL_MIN_ZATOSHI -> {
            // Stays visible until the user actually engages with it — marked seen in
            // MigrationCompleteVM.onDone(), not just for having been displayed.
            MigrationHomeMessageData(plan, isComplete = true)
        }

        // RESIDUE (plan == null, post-completion cleared plan): a leftover Orchard balance above the
        // dust threshold but below the migratable minimum. The engine cannot migrate it
        // (proposeMigrationTransfers returns NothingToMigrate), so a "Migrate now" prompt here would
        // tap into a guaranteed failure. Present it as "migration completed" instead and route to
        // MigrationCompleteScreen, whose residue flow lets the user LOCK it or MIGRATE it anyway.
        // Real Orchard-only balance (not the combined Sapling+Orchard spendableShieldedBalance).
        // The reported Orchard balance is the *spendable* balance, which excludes locked notes
        // (librustzcash get_wallet_summary buckets a spendable-but-locked note into locked_value,
        // not spendable_value), so once the user locks the residue this branch stops firing on its
        // own — no separate locked-state signal is needed to make the prompt go away.
        plan == null &&
            orchardBalanceZatoshi > dustThresholdZatoshi &&
            orchardBalanceZatoshi < MIGRATION_RESIDUAL_MIN_ZATOSHI -> {
            MigrationHomeMessageData(plan = null, isComplete = true)
        }

        // Real Orchard-only balance (not the combined Sapling+Orchard spendableShieldedBalance —
        // this must never fire for a wallet whose Orchard balance is 0, even with real Sapling
        // funds). Falls through here regardless of what sdkState says once plan is null — covers
        // both "never migrated" and "a round finished, more residual balance needs another round."
        // Gated on the migratable minimum, not the dust threshold: only fire "Migrate now" when the
        // balance is genuinely migratable (>= MIGRATION_RESIDUAL_MIN_ZATOSHI). Anything in the
        // [dust, min) gap is a residue and was already handled by the RESIDUE branch above.
        orchardBalanceZatoshi >= MIGRATION_RESIDUAL_MIN_ZATOSHI && plan == null -> {
            MigrationHomeMessageData(null)
        }

        else -> {
            null
        }
    }
}
