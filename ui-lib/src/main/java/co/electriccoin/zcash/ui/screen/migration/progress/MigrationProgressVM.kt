package co.electriccoin.zcash.ui.screen.migration.progress

import android.content.Context
import androidx.lifecycle.ViewModel
import cash.z.ecc.android.sdk.MigrationTransferStates
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.model.LceState
import co.electriccoin.zcash.ui.common.model.migration.MigrationTransfer
import co.electriccoin.zcash.ui.common.model.migration.MigrationTransferStatus
import co.electriccoin.zcash.ui.common.model.mutableLce
import co.electriccoin.zcash.ui.common.model.stateIn
import co.electriccoin.zcash.ui.common.model.withLce
import co.electriccoin.zcash.ui.common.model.toStorageKeyId
import co.electriccoin.zcash.ui.common.repository.ExchangeRateRepository
import co.electriccoin.zcash.ui.common.repository.MigrationPlanRepository
import co.electriccoin.zcash.ui.common.usecase.ErrorMapperUseCase
import co.electriccoin.zcash.ui.common.usecase.GetOrchardMigrationSdkUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.common.wallet.ExchangeRateState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.stringResByDynamicCurrencyNumber
import co.electriccoin.zcash.ui.common.model.guardLoading
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.model.migration.MigrationPlan
import co.electriccoin.zcash.ui.common.model.migration.formatMigrationDuration
import co.electriccoin.zcash.ui.common.model.migration.withLiveState
import co.electriccoin.zcash.ui.common.provider.LastNetworkActivityStorageProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.screen.migration.sending.MigrationSendingArgs
import co.electriccoin.zcash.work.LANE_A_SYNC_TIMEOUT
import co.electriccoin.zcash.work.MigrationScheduler
import cash.z.ecc.android.sdk.ext.convertZatoshiToZec
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import java.math.BigDecimal
import java.math.MathContext
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class MigrationProgressVM(
    private val getOrchardMigrationSdk: GetOrchardMigrationSdkUseCase,
    private val getSelectedWalletAccount: GetSelectedWalletAccountUseCase,
    private val migrationPlanRepository: MigrationPlanRepository,
    private val navigationRouter: NavigationRouter,
    private val exchangeRateRepository: ExchangeRateRepository,
    private val errorStateMapper: ErrorMapperUseCase,
    private val synchronizerProvider: SynchronizerProvider,
    private val lastNetworkActivity: LastNetworkActivityStorageProvider,
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
            // Measured block rate for the height->wall-clock re-projection — the 75s default
            // turned minute-scale testnet schedules into "~1 hour" rows (caught live 28.7.).
            val secondsPerBlock = getOrchardMigrationSdk()?.estimatedSecondsPerBlock() ?: 75L
            plan?.let { createState(it.withLiveState(liveStates, secondsPerBlock), rate, reallyOverdue) }
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

    // MigrationPlanRepository's per-transfer status/scheduledAt is a display cache, written once
    // at propose/commit time. Polling the SDK's own persisted state directly, the same way
    // reallyOverdueFlow() already does for the overdue check, keeps the displayed schedule true
    // to the engine — the single source of truth for the plan — regardless of what the cache
    // last recorded.
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

    // "Reschedule" no longer mutates the plan — a missed-but-unexpired transfer needs NO plan
    // change by design (ZIP 374: the signature does not cover the anchor, so it proves late
    // against its committed boundary and broadcasts late; the engine is the single source of
    // truth). New semantics: SYNC NOW — run the same sync + finalizeReadyTransfers pass Lane A
    // does, in the foreground, so the missing proof falls out immediately — then let the transfer
    // go out in the next live window (background worker, or next app open).
    private fun onReschedule() = sendLce.execute {
        val sdk = getOrchardMigrationSdk() ?: error("MigrationProgressVM: no wallet available to sync")
        val accountKeyId = getSelectedWalletAccount().sdkAccount.accountUuid.toStorageKeyId()
        if (sdk.isSyncBlocked().first()) {
            // Post-broadcast privacy gate — same guard Lane A honours; the re-arm below still
            // gives the transfer its next window once the gate lifts.
            Twig.debug { "MIGRATION_DIAG MigrationProgressVM.onReschedule: privacy gate active — skipping the foreground sync." }
        } else {
            val burst = synchronizerProvider.getSynchronizerOrNull()?.syncToTip(timeout = LANE_A_SYNC_TIMEOUT)
            Twig.debug { "MIGRATION_DIAG MigrationProgressVM.onReschedule: syncToTip result=$burst" }
            val proved = sdk.finalizeReadyTransfers()
            Twig.debug { "MIGRATION_DIAG MigrationProgressVM.onReschedule: proved=$proved" }
            lastNetworkActivity.stampNow()
        }
        // Re-arm Lane B for the next live window: after the privacy quiet gap from the sync just
        // performed (Lane B's own preflight re-checks the gap from the fresh stamp regardless).
        val reArm = sdk.privacySyncBufferDuration()
        MigrationScheduler(context).schedule(accountKeyId, reArm)
        Twig.debug { "MIGRATION_DIAG MigrationProgressVM.onReschedule: Lane B re-armed in $reArm" }
        navigationRouter.back()
    }

    private fun onDone() = navigationRouter.backToRoot()

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
