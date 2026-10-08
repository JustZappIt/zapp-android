// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd.reverse

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.privateusd.LocalCurrency
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.privateusd.message
import co.electriccoin.zcash.ui.screen.privateusd.progress.PrivateUsdProblemState
import co.electriccoin.zcash.ui.screen.privateusd.progress.PrivateUsdProgressState
import xyz.justzappit.offramp.atomicswap.ReverseApproval
import xyz.justzappit.offramp.atomicswap.ReversePhase
import xyz.justzappit.offramp.atomicswap.ReverseSwapRecord
import xyz.justzappit.offramp.atomicswap.ReverseSwapResult
import xyz.justzappit.offramp.atomicswap.ReverseSwapStatus

/** What the reverse conversion's screen does when tapped. */
internal interface PrivateUsdReverseActions {
    fun review()

    /** Goes ahead with the previewed conversion [index] and pays for it. */
    fun convert(index: Int)

    fun fund(index: Int)

    fun ready(index: Int)

    fun cancel(index: Int)

    fun refunds()

    fun newQuote()

    fun newConversion()

    fun back()
}

/** The reverse conversion's buttons, review and progress, as its record and form have them. */
internal class PrivateUsdReverseMapper(
    private val terms: PrivateUsdReverseTerms,
    private val actions: PrivateUsdReverseActions,
) {
    /** Null while the conversion goes on by itself: the screen follows it. */
    fun primary(
        record: ReverseSwapRecord?,
        form: ReverseForm,
        canPay: Boolean,
        isExpired: Boolean,
    ): ButtonState? =
        when {
            form.step == ReverseStep.Quoting -> {
                ButtonState(stringRes(R.string.convert_quote_loading), isEnabled = false, isLoading = true)
            }

            record == null || record.status == ReverseSwapStatus.Previewed -> {
                preview(record, form, canPay, isExpired)
            }

            else -> {
                underWay(record, canPay)
            }
        }?.let { button ->
            button.copy(
                isEnabled = button.isEnabled && !form.isActing && !form.isCancelling,
                isLoading = button.isLoading || form.isActing,
            )
        }

    fun review(
        record: ReverseSwapRecord,
        currency: LocalCurrency,
    ): PrivateUsdReverseReviewState? {
        val cost = record.cost ?: return null
        return PrivateUsdReverseReviewState(
            debit = terms.format(cost.debit, currency),
            escrow = terms.format(record.quote.terms.amount, currency),
            railgunFee = terms.format(cost.railgunFee, currency),
            broadcasterFee = terms.format(cost.broadcasterFee, currency),
            receive = stringRes(Zatoshi(record.receivedZat())),
        )
    }

    fun progress(
        record: ReverseSwapRecord,
        conversion: ReverseConversion,
        form: ReverseForm,
        currency: LocalCurrency,
        primary: ButtonState?,
    ): PrivateUsdProgressState =
        reverseProgress(
            record = record,
            paid = terms.format(record.debit, currency),
            received = stringRes(Zatoshi(record.receivedZat())),
            callOff = callOff(record, form, conversion.hasRefunds),
            problem =
                conversion.swap.problem
                    ?.takeIf { record.underWay }
                    ?.let { PrivateUsdProblemState(it.message(), onRetry = null) },
            zcashWait = conversion.swap.zcashWait?.takeIf { record.underWay },
            error = form.error,
            primary = primary,
            info = terms.info(currency),
            isBackEnabled = form.isBackEnabled,
            onBack = actions::back,
        )

    private fun preview(
        record: ReverseSwapRecord?,
        form: ReverseForm,
        canPay: Boolean,
        isExpired: Boolean,
    ): ButtonState =
        when {
            form.isReviewing && isExpired -> {
                ButtonState(stringRes(R.string.convert_new_quote), onClick = actions::newQuote)
            }

            form.isReviewing -> {
                ButtonState(stringRes(R.string.private_usd_action_convert), isEnabled = record != null && canPay) {
                    record?.let { actions.convert(it.index) }
                }
            }

            else -> {
                ButtonState(stringRes(R.string.convert_review), isEnabled = record != null && canPay) {
                    actions.review()
                }
            }
        }

    private fun underWay(
        record: ReverseSwapRecord,
        canPay: Boolean,
    ): ButtonState? {
        val status = record.status
        return when {
            status is ReverseSwapStatus.Over -> {
                ButtonState(stringRes(R.string.reverse_new), onClick = actions::newConversion)
            }

            (status as? ReverseSwapStatus.UnderWay)?.awaiting == ReverseApproval.FUNDING -> {
                ButtonState(stringRes(R.string.private_usd_action_convert), isEnabled = canPay) {
                    actions.fund(record.index)
                }
            }

            (status as? ReverseSwapStatus.UnderWay)?.awaiting == ReverseApproval.SETTLEMENT -> {
                ButtonState(stringRes(R.string.reverse_ready)) { actions.ready(record.index) }
            }

            else -> {
                null
            }
        }
    }

    // Cancelling while that can still stop the conversion, or recovering its refund once that can be done; neither
    // while another step runs.
    private fun callOff(
        record: ReverseSwapRecord,
        form: ReverseForm,
        hasRefunds: Boolean,
    ): ButtonState? =
        when {
            form.isCancelling -> {
                ButtonState(stringRes(refundAction(record)), isEnabled = false, isLoading = true)
            }

            (record.status as? ReverseSwapStatus.UnderWay)?.cancellable == true && form.canRequestRefund -> {
                ButtonState(stringRes(refundAction(record))) { actions.cancel(record.index) }
            }

            form.canAct && record.status == ReverseSwapStatus.Over(ReverseSwapResult.REFUNDED) && hasRefunds -> {
                ButtonState(stringRes(R.string.refunds_view), onClick = actions::refunds)
            }

            else -> {
                null
            }
        }

    private fun refundAction(record: ReverseSwapRecord): Int =
        when (record.phase) {
            ReversePhase.ACCEPTING, ReversePhase.AWAITING_FUNDING -> {
                if (record.funding == null) R.string.reverse_cancel else R.string.reverse_request_refund
            }

            else -> {
                R.string.reverse_request_refund
            }
        }
}
