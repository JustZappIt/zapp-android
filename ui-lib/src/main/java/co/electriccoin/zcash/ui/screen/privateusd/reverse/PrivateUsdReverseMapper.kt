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

    fun rescue(index: Int)

    fun refresh()

    fun newQuote()

    fun newConversion()

    fun back()
}

/** The reverse conversion's buttons, review and progress, as its record and form have them. */
internal class PrivateUsdReverseMapper(
    private val terms: PrivateUsdReverseTerms,
    private val actions: PrivateUsdReverseActions,
) {
    fun primary(
        record: ReverseSwapRecord?,
        form: ReverseForm,
        canPay: Boolean,
        isExpired: Boolean,
    ): ButtonState {
        val button =
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
            }
        return button.copy(
            isEnabled = button.isEnabled && !form.isActing,
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
            broadcasterFee = cost.broadcasterFee?.let { terms.format(it, currency) },
            receive = stringRes(Zatoshi(record.receivedZat())),
        )
    }

    fun progress(
        record: ReverseSwapRecord,
        conversion: ReverseConversion,
        form: ReverseForm,
        currency: LocalCurrency,
        primary: ButtonState,
    ): PrivateUsdProgressState =
        reverseProgress(
            record = record,
            paid = terms.format(record.debit, currency),
            received = stringRes(Zatoshi(record.receivedZat())),
            callOff = callOff(record, form, conversion.rescuable),
            problem =
                conversion.swap.problem
                    ?.takeIf { record.underWay }
                    ?.let { PrivateUsdProblemState(it.message(), onRetry = null) },
            error = form.error,
            primary = primary,
            info = terms.info(currency),
            isBackEnabled = form.isBackEnabled,
            onBack = actions::back,
        )

    fun rescue(
        index: Int,
        form: ReverseForm
    ) = ButtonState(stringRes(R.string.reverse_rescue), isEnabled = !form.isActing) { actions.rescue(index) }

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
    ): ButtonState {
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
                ButtonState(stringRes(R.string.reverse_refresh), onClick = actions::refresh)
            }
        }
    }

    // Cancelling while that can still stop the conversion, or recovering its refund once that can be done.
    private fun callOff(
        record: ReverseSwapRecord,
        form: ReverseForm,
        rescuable: Int?,
    ): ButtonState? =
        when {
            (record.status as? ReverseSwapStatus.UnderWay)?.cancellable == true -> {
                ButtonState(stringRes(R.string.reverse_cancel), isEnabled = !form.isActing) {
                    actions.cancel(record.index)
                }
            }

            record.status == ReverseSwapStatus.Over(ReverseSwapResult.REFUNDED) && rescuable == record.index -> {
                rescue(record.index, form)
            }

            else -> {
                null
            }
        }
}
