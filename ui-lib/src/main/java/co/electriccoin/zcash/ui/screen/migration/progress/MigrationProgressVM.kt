package co.electriccoin.zcash.ui.screen.migration.progress

import android.content.Context
import androidx.lifecycle.ViewModel
import cash.z.ecc.android.sdk.MigrationTransferStates
import cash.z.ecc.android.sdk.TransferProposal
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.model.LceState
import co.electriccoin.zcash.ui.common.model.migration.MigrationTransfer
import co.electriccoin.zcash.ui.common.model.migration.MigrationTransferStatus
import co.electriccoin.zcash.ui.common.model.mutableLce
import co.electriccoin.zcash.ui.common.model.stateIn
import co.electriccoin.zcash.ui.common.model.withLce
import co.electriccoin.zcash.ui.common.repository.ExchangeRateRepository
import co.electriccoin.zcash.ui.common.repository.MigrationPlanRepository
import co.electriccoin.zcash.ui.common.usecase.ErrorMapperUseCase
import co.electriccoin.zcash.ui.common.usecase.GetOrchardMigrationSdkUseCase
import co.electriccoin.zcash.ui.common.wallet.ExchangeRateState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.stringResByDynamicCurrencyNumber
import co.electriccoin.zcash.ui.common.model.guardLoading
import co.electriccoin.zcash.ui.common.model.migration.MigrationPlan
import co.electriccoin.zcash.ui.common.model.migration.estimatedSecondsBetweenHeights
import co.electriccoin.zcash.ui.common.model.migration.formatMigrationDuration
import co.electriccoin.zcash.ui.common.model.migration.withLiveState
import co.electriccoin.zcash.ui.screen.migration.sending.MigrationSendingArgs
import co.electriccoin.zcash.work.MigrationScheduler
import cash.z.ecc.android.sdk.ext.convertZatoshiToZec
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import java.math.BigDecimal
import java.math.MathContext
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class MigrationProgressVM(
    private val getOrchardMigrationSdk: GetOrchardMigrationSdkUseCase,
    private val migrationPlanRepository: MigrationPlanRepository,
    private val navigationRouter: NavigationRouter,
    private val exchangeRateRepository: ExchangeRateRepository,
    private val errorStateMapper: ErrorMapperUseCase,
    private val context: Context,
) : ViewModel() {

    private val sendLce = mutableLce<Unit>()

    val state: StateFlow<LceState<MigrationProgressState>> =
        combine(
            migrationPlanRepository.observe(),
            exchangeRateRepository.state,
            reallyOverdueFlow(),
            liveTransferStatesFlow(),
        ) { plan, rate, reallyOverdue, liveStates ->
            plan?.let { createState(it.withLiveState(liveStates), rate, reallyOverdue) }
        }.withLce(sendLce, errorStateMapper::mapToState)
            .stateIn(this)

    // plan.nextPending.scheduledAt is a wall-clock estimate computed once, at schedule-persist
    // time, from the Rust schedule's block-height deltas — it can drift from the actual
    // height-based due time the SDK enforces (most obviously under a device/emulator wall-clock
    // jump; see zcash_pool_migration design spec §4.6). Gating Send Now/Reschedule on that
    // estimate alone let the button appear before the transfer was really due, and clicking it
    // then crashed (rescheduleOverdueTransfer() found nothing pending, since the SDK's own
    // height-based check correctly disagreed). Polling the SDK's authoritative
    // hasOverdueTransfers() — the same check the background sync-block mechanism relies on —
    // keeps this screen's notion of "overdue" consistent with what the SDK will actually act on.
    private fun reallyOverdueFlow(): Flow<Boolean> =
        flow {
            while (true) {
                val sdk = getOrchardMigrationSdk()
                emit(sdk?.hasOverdueTransfers() ?: false)
                delay(OVERDUE_RECHECK_INTERVAL)
            }
        }

    // MigrationPlanRepository's per-transfer status/scheduledAt is a cache, written once at
    // propose/commit time and only ever updated by whichever caller remembers to write through it
    // (production rescheduleOverdueTransfer() and the debug-only debugRescheduleTransfers() both
    // currently forget — see MIGRATION_DIAG "next transfer in N hours never changes" report).
    // Polling the SDK's own persisted state directly, the same way reallyOverdueFlow() already
    // does for the overdue check, makes the displayed schedule correct regardless of which caller
    // last rescheduled.
    private fun liveTransferStatesFlow(): Flow<MigrationTransferStates?> =
        flow {
            while (true) {
                val sdk = getOrchardMigrationSdk()
                emit(sdk?.getMigrationTransferStates())
                delay(OVERDUE_RECHECK_INTERVAL)
            }
        }

    // withLiveState() (correlating by stable transfer id, never array index — see its doc) now
    // lives in MigrationPlan.kt, shared with MigrationAttention.kt's affectedTransferIndices().

    fun navigateBack() = navigationRouter.back()

    private fun createState(
        plan: MigrationPlan,
        exchangeRateState: ExchangeRateState,
        reallyOverdue: Boolean,
    ): MigrationProgressState {
        val now = Clock.System.now()
        val next = plan.nextPending
        val hasOverdue = next != null && reallyOverdue
        val isResume = hasOverdue && plan.completedCount > 0
        val overdueH = if (next != null) overdueHours(next, now) else 0L

        val span = (plan.transfers.maxOfOrNull { it.scheduledAtEpochSeconds } ?: plan.createdAtEpochSeconds) -
            plan.createdAtEpochSeconds
        val subtitle = when {
            plan.isComplete -> "All ${plan.totalCount} transfers are complete."
            isResume -> "Transfer ${plan.completedCount + 1} of ${plan.totalCount} was scheduled ${overdueH}h ago but wasn't sent. Send now or reschedule."
            else -> "Your balance splits into ${plan.totalCount} transfers over " +
                "${formatMigrationDuration(span)}. There are " +
                "${plan.totalCount - plan.completedCount} remaining transfers."
        }

        val totalZatoshi = plan.transfers.sumOf { it.amountZatoshi }
        return MigrationProgressState(
            title = stringRes(if (isResume) "Resume Migration" else "Migration Progress"),
            subtitle = stringRes(subtitle),
            totalAmount = stringRes(Zatoshi(totalZatoshi)),
            totalFiatAmount = fiatAmount(Zatoshi(totalZatoshi), exchangeRateState),
            transfers = plan.transfers.map { t ->
                MigrationProgressTransferState(
                    index = t.index + 1,
                    amount = stringRes(Zatoshi(t.amountZatoshi)),
                    fiatAmount = fiatAmount(Zatoshi(t.amountZatoshi), exchangeRateState),
                    statusLabel = transferLabel(t, now),
                    isOverdue = t.status == MigrationTransferStatus.PENDING && t.scheduledAt <= now,
                    isSent = t.status == MigrationTransferStatus.SENT,
                )
            },
            isComplete = plan.isComplete,
            hasOverdue = hasOverdue,
            onBack = ::onBack,
            // Figma B4 (Updated Migration Plan — normal in-progress) has no Send Now button at
            // all; it only appears on B8 (Resume Migration) when a transfer is actually overdue.
            onSendNow = if (hasOverdue) { { onSendNow(plan) } } else null,
            onReschedule = if (hasOverdue) ::onReschedule else null,
            onDone = if (plan.isComplete) ::onDone else null,
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
    private fun onSendNow(plan: MigrationPlan) = navigationRouter.forward(MigrationSendingArgs)

    private fun onReschedule() = sendLce.execute {
        // rescheduleOverdueTransfer() persists the new schedule itself (SDK-owned) — sync
        // unblocking follows automatically via isSyncBlocked() once the plan changes. The VM
        // still owns WorkManager scheduling for the new time, same as everywhere else. Background
        // delivery is scheduled unconditionally — see MigrationScheduler/
        // FinalizeMigrationScheduleUseCase for why this no longer depends on a delivery-mode flag.
        val sdk = getOrchardMigrationSdk() ?: error("MigrationProgressVM: no wallet available to reschedule")
        val proposal = sdk.rescheduleOverdueTransfer()
        val delay = delayUntil(proposal)
        MigrationScheduler(context).schedule(delay)
        navigationRouter.back()
    }

    private fun onDone() = navigationRouter.backToRoot()

    private fun delayUntil(proposal: TransferProposal): Duration {
        val remaining = estimatedSecondsBetweenHeights(proposal.anchorHeight, proposal.nextExecutableAfterHeight)
        return if (remaining <= 0) 0.seconds else remaining.seconds
    }

    private fun transferLabel(t: MigrationTransfer, now: Instant): StringResource =
        when (t.status) {
            MigrationTransferStatus.SENT -> {
                val agoMinutes = (now - t.scheduledAt).inWholeMinutes
                when {
                    agoMinutes < 1 -> stringRes("Sent recently")
                    agoMinutes < 60 -> stringRes("Sent $agoMinutes min ago")
                    else -> stringRes("Sent ${agoMinutes / 60}h ago")
                }
            }
            MigrationTransferStatus.PENDING -> {
                val scheduled = t.scheduledAt
                when {
                    scheduled <= now -> stringRes("Overdue · ${overdueHours(t, now)}h ago")
                    else -> {
                        val minutesLeft = (scheduled - now).inWholeMinutes
                        when {
                            minutesLeft <= 0 -> stringRes("Ready now")
                            minutesLeft < 60 -> stringRes("~$minutesLeft min")
                            else -> stringRes("~${minutesLeft / 60} hours")
                        }
                    }
                }
            }
        }

    private fun overdueHours(t: MigrationTransfer, now: Instant) =
        (now - t.scheduledAt).inWholeHours.coerceAtLeast(0)

    companion object {
        private val OVERDUE_RECHECK_INTERVAL = 15.seconds
    }
}
