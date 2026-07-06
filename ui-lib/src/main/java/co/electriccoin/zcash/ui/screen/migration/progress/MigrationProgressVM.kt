package co.electriccoin.zcash.ui.screen.migration.progress

import android.content.Context
import androidx.lifecycle.ViewModel
import cash.z.ecc.android.sdk.NetworkPrivacyOptions
import cash.z.ecc.android.sdk.OrchardMigrationSdk
import cash.z.ecc.android.sdk.TransferResult
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.model.LceState
import co.electriccoin.zcash.ui.common.model.migration.MigrationDeliveryMode
import co.electriccoin.zcash.ui.common.model.migration.MigrationTransfer
import co.electriccoin.zcash.ui.common.model.migration.MigrationTransferFailureState
import co.electriccoin.zcash.ui.common.model.migration.MigrationTransferStatus
import co.electriccoin.zcash.ui.common.model.migration.migrationFailureMessage
import co.electriccoin.zcash.ui.common.model.mutableLce
import co.electriccoin.zcash.ui.common.model.stateIn
import co.electriccoin.zcash.ui.common.model.withLce
import co.electriccoin.zcash.ui.common.repository.ExchangeRateRepository
import co.electriccoin.zcash.ui.common.repository.MigrationPlanRepository
import co.electriccoin.zcash.ui.common.usecase.ErrorMapperUseCase
import co.electriccoin.zcash.ui.common.wallet.ExchangeRateState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.stringResByDynamicCurrencyNumber
import co.electriccoin.zcash.ui.common.model.guardLoading
import co.electriccoin.zcash.ui.common.model.migration.MigrationPlan
import co.electriccoin.zcash.ui.common.model.migration.formatMigrationDuration
import co.electriccoin.zcash.work.MigrationScheduler
import cash.z.ecc.android.sdk.ext.convertZatoshiToZec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import java.math.BigDecimal
import java.math.MathContext
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class MigrationProgressVM(
    private val sdk: OrchardMigrationSdk,
    private val migrationPlanRepository: MigrationPlanRepository,
    private val navigationRouter: NavigationRouter,
    private val exchangeRateRepository: ExchangeRateRepository,
    private val errorStateMapper: ErrorMapperUseCase,
    private val context: Context,
) : ViewModel() {

    private val sendLce = mutableLce<Unit>()
    private val sendNowFailure = MutableStateFlow<TransferResult?>(null)

    val state: StateFlow<LceState<MigrationProgressState>> =
        combine(migrationPlanRepository.observe(), exchangeRateRepository.state, sendNowFailure) { plan, rate, failure ->
            plan?.let { createState(it, rate, failure) }
        }.withLce(sendLce, errorStateMapper::mapToState)
            .stateIn(this)

    fun navigateBack() = navigationRouter.back()

    private fun createState(
        plan: MigrationPlan,
        exchangeRateState: ExchangeRateState,
        failure: TransferResult?,
    ): MigrationProgressState {
        val now = Clock.System.now()
        val next = plan.nextPending
        val hasOverdue = next != null && next.scheduledAt <= now
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

        return MigrationProgressState(
            title = stringRes(if (isResume) "Resume Migration" else "Migration Progress"),
            subtitle = stringRes(subtitle),
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
            onSendNow = if (hasOverdue) ::onSendNow else null,
            onReschedule = if (hasOverdue) ::onReschedule else null,
            onDone = if (plan.isComplete) ::onDone else null,
            sendNowFailureSheet = failure?.let {
                MigrationTransferFailureState(
                    message = migrationFailureMessage(it),
                    onRetry = { sendNowFailure.value = null; onSendNow() },
                    onDismiss = { sendNowFailure.value = null },
                )
            },
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

    private fun onSendNow() = sendLce.execute {
        val plan = migrationPlanRepository.load() ?: return@execute
        // Privacy buffer bookkeeping (keeping sync paused post-broadcast) is entirely SDK-owned
        // — the SDK notices this transfer was overdue and sets it internally. This VM only
        // reacts to success (re-arm the next window and navigate away) or failure (retry sheet).
        when (val result = sdk.executeNextPendingTransfer(NetworkPrivacyOptions(useTor = plan.useTor))) {
            is TransferResult.Success -> {
                scheduleNextWindowIfAny(plan.deliveryMode)
                navigationRouter.back()
            }
            null -> Unit
            else -> sendNowFailure.value = result
        }
    }

    private fun onReschedule() = sendLce.execute {
        val plan = migrationPlanRepository.load()
        // rescheduleOverdueTransfer() persists the new schedule itself (SDK-owned) — sync
        // unblocking follows automatically via isSyncBlocked() once the plan changes. The VM
        // still owns WorkManager scheduling for the new time, same as everywhere else. A MANUAL
        // plan must only ever get a notify-only job here, never a real send worker — otherwise
        // manual mode silently degrades into background auto-send after any reschedule.
        val proposal = sdk.rescheduleOverdueTransfer()
        val delay = delayUntil(proposal.nextExecutableAfterHeight)
        if (plan?.deliveryMode == MigrationDeliveryMode.MANUAL) {
            MigrationScheduler(context).scheduleNotifyOnly(delay)
        } else {
            MigrationScheduler(context).schedule(delay)
        }
        navigationRouter.back()
    }

    private fun onDone() = navigationRouter.backToRoot()

    // A SCHEDULED plan re-arms the real send worker for the next window; a MANUAL plan only ever
    // gets a notify-only job — the user must open the app and confirm each subsequent transfer.
    // Plain suspend helper (not sendLce.execute) — always called from inside an existing
    // sendLce.execute block, so it must not open a second one.
    private suspend fun scheduleNextWindowIfAny(deliveryMode: MigrationDeliveryMode) {
        val next = migrationPlanRepository.load()?.nextPending ?: return
        val delay = delayUntil(next.scheduledAtEpochSeconds)
        if (deliveryMode == MigrationDeliveryMode.MANUAL) {
            MigrationScheduler(context).scheduleNotifyOnly(delay)
        } else {
            MigrationScheduler(context).schedule(delay)
        }
    }

    private fun delayUntil(epochSeconds: Long): Duration {
        val remaining = epochSeconds - Clock.System.now().epochSeconds
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
}
