package co.electriccoin.zcash.ui.screen.swap.upi.progress

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.zapp.ZappStep
import co.electriccoin.zcash.ui.design.component.zapp.ZappStepStatus
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import xyz.justzappit.offramp.orchestrator.OfframpStatus
import xyz.justzappit.offramp.orchestrator.OfframpStep
import xyz.justzappit.offramp.orchestrator.step
import xyz.justzappit.offramp.p2p.CurrencyCode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Pure mapper: orchestrator [OfframpStatus] → ordered list of [ZappStep] rows for the UI.
 *
 * Extracted from [UpiOfframpProgressVM] so it can be unit-tested without standing up a ViewModel
 * + Dispatchers.Main, and so the VM stays focused on flow plumbing. All inputs are values; the
 * only effect is producing the step list.
 */
internal fun buildProgressSteps(
    status: OfframpStatus,
    currency: CurrencyCode,
    fundedFromBaseObserved: Boolean = false,
    bridgingObserved: Boolean = false,
): List<ZappStep> {
    val order =
        OfframpStep.UI_PROGRESS.filter { step ->
            step != OfframpStep.FUNDING || shouldShowFundingStep(status, fundedFromBaseObserved, bridgingObserved)
        }
    val currentStep = status.step.takeIf { status !is OfframpStatus.Failed }
    val failedStep = (status as? OfframpStatus.Failed)?.step
    return order.mapIndexed { index, step ->
        ZappStep(
            label = labelFor(step, status, currency),
            status = computeStepStatus(index, order, currentStep, failedStep, status),
            detailLines = stepDetail(status, step),
        )
    }
}

private fun shouldShowFundingStep(
    status: OfframpStatus,
    fundedFromBaseObserved: Boolean,
    bridgingObserved: Boolean,
): Boolean =
    when {
        status is OfframpStatus.FundedFromBase || fundedFromBaseObserved -> false
        status is OfframpStatus.BridgingFunds || bridgingObserved -> true
        status is OfframpStatus.Failed && status.step == OfframpStep.FUNDING -> true
        status is OfframpStatus.FundsRecovered -> true
        else -> false
    }

private fun labelFor(
    step: OfframpStep,
    status: OfframpStatus,
    currency: CurrencyCode,
): StringResource =
    when {
        // The final row reads "Waiting for merchant payment" while polling; once COMPLETED it would
        // misleadingly still say "waiting", so flip it to a done label.
        step == OfframpStep.WAITING_FOR_COMPLETION && status is OfframpStatus.Completed -> {
            stringRes(R.string.upi_offramp_step_completed)
        }

        else -> {
            stepLabel(step, currency)
        }
    }

private fun computeStepStatus(
    index: Int,
    order: List<OfframpStep>,
    currentStep: OfframpStep?,
    failedStep: OfframpStep?,
    status: OfframpStatus,
): ZappStepStatus {
    if (failedStep != null) {
        val failedAt = uiIndexFor(failedStep, order)
        return when {
            index == failedAt -> ZappStepStatus.Failed
            index < failedAt -> ZappStepStatus.Completed
            else -> ZappStepStatus.Pending
        }
    }
    // Cancelled is terminal: the WAITING_FOR_COMPLETION row didn't complete (paint Failed);
    // everything before it did happen on-chain (paint Completed).
    if (status is OfframpStatus.Cancelled) {
        val cancelledAt = uiIndexFor(OfframpStep.WAITING_FOR_COMPLETION, order)
        return when {
            index == cancelledAt -> ZappStepStatus.Failed
            index < cancelledAt -> ZappStepStatus.Completed
            else -> ZappStepStatus.Pending
        }
    }
    // Completed is terminal success: every row is done, including the final completion row (which the
    // status->step mapping otherwise reports as the "current" step and would paint InProgress).
    if (status is OfframpStatus.Completed) return ZappStepStatus.Completed
    if (currentStep == null) return ZappStepStatus.Pending
    val currentIndex = order.indexOf(displayedStepFor(currentStep))
    if (currentIndex < 0) {
        val nextVisibleIndex = nextVisibleIndexAfter(currentStep, order)
        return when {
            index < nextVisibleIndex -> ZappStepStatus.Completed
            else -> ZappStepStatus.Pending
        }
    }
    return when {
        index < currentIndex -> ZappStepStatus.Completed
        index == currentIndex -> ZappStepStatus.InProgress
        else -> ZappStepStatus.Pending
    }
}

/**
 * Maps a canonical [OfframpStep] to its position in [order]. INITIALIZATION collapses to
 * SELECTING_CIRCLE; ENCRYPTING_UPI collapses to SENDING_UPI (these are internal steps not
 * surfaced as separate UI rows).
 */
private fun uiIndexFor(step: OfframpStep, order: List<OfframpStep>): Int = order.indexOf(displayedStepFor(step)).coerceAtLeast(0)

private fun displayedStepFor(step: OfframpStep): OfframpStep =
    when (step) {
        OfframpStep.INITIALIZATION -> OfframpStep.SELECTING_CIRCLE
        OfframpStep.ENCRYPTING_UPI -> OfframpStep.SENDING_UPI
        else -> step
    }

private fun nextVisibleIndexAfter(step: OfframpStep, order: List<OfframpStep>): Int {
    val canonicalIndex = OfframpStep.UI_PROGRESS.indexOf(displayedStepFor(step))
    if (canonicalIndex < 0) return 0
    val nextStep =
        OfframpStep.UI_PROGRESS
            .drop(canonicalIndex + 1)
            .firstOrNull { it in order }
            ?: return order.size
    return order.indexOf(nextStep)
}

internal fun stepLabel(
    step: OfframpStep,
    currency: CurrencyCode,
): StringResource =
    when (step) {
        OfframpStep.INITIALIZATION -> stringRes(R.string.upi_offramp_step_init)
        OfframpStep.SELECTING_CIRCLE -> stringRes(R.string.upi_offramp_step_selecting_circle)
        OfframpStep.FUNDING -> stringRes(R.string.upi_offramp_step_funding)
        OfframpStep.APPROVING_USDC -> stringRes(R.string.upi_offramp_step_approve)
        OfframpStep.PLACING_ORDER -> stringRes(R.string.upi_offramp_step_place_order)
        OfframpStep.WAITING_FOR_ACCEPTANCE -> stringRes(R.string.upi_offramp_step_wait_acceptance)
        OfframpStep.WAITING_FOR_PAYMENT_DETAILS -> stringRes(R.string.upi_offramp_step_scan_qr)
        OfframpStep.ENCRYPTING_UPI -> stringRes(R.string.upi_offramp_step_encrypting_upi, currency.railName)
        OfframpStep.SENDING_UPI -> stringRes(R.string.upi_offramp_step_send_upi, currency.railName)
        OfframpStep.WAITING_FOR_COMPLETION -> stringRes(R.string.upi_offramp_step_wait_completion)
    }

// Rail names are brand nouns, identical across locales; the rail follows the order's currency,
// same dispatch as PaymentQrParser.
private val CurrencyCode.railName: String
    get() =
        when (this) {
            CurrencyCode.Inr -> "UPI"
            CurrencyCode.Brl -> "PIX"
            CurrencyCode.Idr -> "QRIS"
            CurrencyCode.Ars -> "MercadoPago"
            CurrencyCode.Ven -> "Pago Móvil"
            CurrencyCode.Ngn -> "NIP"
            CurrencyCode.Cop -> "Transferencia"
            CurrencyCode.Bob -> "QR Simple"
            CurrencyCode.Cup -> "Transfermóvil"
            CurrencyCode.Ecu -> "DeUna"
            CurrencyCode.Pen -> "Yape/Plin"
            CurrencyCode.Php -> "QR Ph"
        }

private fun stepDetail(status: OfframpStatus, step: OfframpStep): List<StringResource> =
    when (step) {
        OfframpStep.SELECTING_CIRCLE -> {
            emptyList()
        }

        OfframpStep.FUNDING -> {
            emptyList()
        }

        OfframpStep.APPROVING_USDC -> {
            emptyList()
        }

        OfframpStep.PLACING_ORDER -> {
            emptyList()
        }

        OfframpStep.WAITING_FOR_ACCEPTANCE -> {
            emptyList()
        }

        OfframpStep.WAITING_FOR_PAYMENT_DETAILS -> {
            (status as? OfframpStatus.WaitingForPaymentDetails)
                ?.let {
                    buildList {
                        it.acceptedAtEpochSeconds?.let { ts ->
                            add(stringRes(R.string.upi_offramp_detail_accepted_at, formatClockTime(ts)))
                        }
                    }
                }.orEmpty()
        }

        OfframpStep.SENDING_UPI -> {
            (status as? OfframpStatus.SendingEncryptedUpi)
                ?.let {
                    buildList {
                        it.acceptedAtEpochSeconds?.let { ts ->
                            add(stringRes(R.string.upi_offramp_detail_accepted_at, formatClockTime(ts)))
                        }
                    }
                }.orEmpty()
        }

        OfframpStep.WAITING_FOR_COMPLETION -> {
            buildCompletionDetails(status)
        }

        else -> {
            emptyList()
        }
    }

private fun buildCompletionDetails(status: OfframpStatus): List<StringResource> =
    when (status) {
        is OfframpStatus.WaitingForCompletion -> {
            buildList {
                status.acceptedAtEpochSeconds?.let { ts ->
                    add(stringRes(R.string.upi_offramp_detail_accepted_at, formatClockTime(ts)))
                }
                status.paidAtEpochSeconds?.let { ts ->
                    add(stringRes(R.string.upi_offramp_detail_paid_at, formatClockTime(ts)))
                }
            }
        }

        is OfframpStatus.Completed -> {
            buildList {
                status.paidAtEpochSeconds?.let { ts ->
                    add(stringRes(R.string.upi_offramp_detail_paid_at, formatClockTime(ts)))
                }
                status.completedAtEpochSeconds?.let { ts ->
                    add(stringRes(R.string.upi_offramp_detail_completed_at, formatClockTime(ts)))
                }
            }
        }

        else -> {
            emptyList()
        }
    }

private fun formatClockTime(epochSeconds: Long): String =
    clockFormat.format(Date(epochSeconds * MILLIS_PER_SECOND))

// Locale-stable format so screenshots / fixtures don't drift across devices.
private val clockFormat: SimpleDateFormat =
    SimpleDateFormat("HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getDefault() }

private const val MILLIS_PER_SECOND = 1_000L
