// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd.convert

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapDeployment
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapQuote
import co.electriccoin.zcash.ui.common.privateusd.ConversionCurrency
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdToken
import co.electriccoin.zcash.ui.common.privateusd.format
import co.electriccoin.zcash.ui.common.privateusd.toDecimal
import co.electriccoin.zcash.ui.common.privateusd.tokenAmount
import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.privateusd.PrivateUsdInfo
import co.electriccoin.zcash.ui.screen.privateusd.about

internal data class ConvertForm(
    val phase: PrivateUsdConvertPhase = PrivateUsdConvertPhase.AMOUNT,
    val amount: NumberTextFieldInnerState = NumberTextFieldInnerState(),
    val quote: ConvertQuote = ConvertQuote.None,
    val isConfirming: Boolean = false,
    val error: StringResource? = null,
)

internal sealed interface ConvertQuote {
    data object None : ConvertQuote

    data class Loading(
        val units: Long
    ) : ConvertQuote

    data class Ready(
        val units: Int,
        val quote: AtomicSwapQuote,
    ) : ConvertQuote {
        // Runs out a little early: the driver won't accept a quote about to expire.
        fun secondsLeft(now: Long) = quote.offer.quote.expiresAt - EXPIRY_MARGIN_SECONDS - now

        private companion object {
            const val EXPIRY_MARGIN_SECONDS = 20L
        }
    }

    data class Failed(
        val message: StringResource
    ) : ConvertQuote
}

/** A deployment's amounts, in dollars: what may be converted, and what a quote says. */
internal class PrivateUsdConvertTerms(
    deployment: AtomicSwapDeployment,
    private val token: PrivateUsdToken,
) {
    val duration: StringResource = deployment.expectedDuration.about()
    private val minUnits = deployment.minUnits
    private val maxUnits = deployment.maxUnits
    private val unitDollars = deployment.unitBaseUnits.toBigInteger().toDecimal(token.decimals)

    val limits: StringResource =
        stringRes(
            R.string.convert_limits,
            (unitDollars * minUnits.toBigDecimal()).stripTrailingZeros().toPlainString(),
            (unitDollars * maxUnits.toBigDecimal()).stripTrailingZeros().toPlainString(),
        )

    /** Amounts in the maker's quoted units within the deployment limits, or null. */
    fun units(amount: NumberTextFieldInnerState): Int? {
        val (units, remainder) = amount.amount?.divideAndRemainder(unitDollars) ?: return null
        return units
            .takeIf { remainder.signum() == 0 && it >= minUnits.toBigDecimal() && it <= maxUnits.toBigDecimal() }
            ?.toInt()
    }

    fun isInvalid(amount: NumberTextFieldInnerState): Boolean =
        !amount.innerTextFieldState.value.isEmpty() && units(amount) == null

    fun isShort(
        ready: ConvertQuote.Ready,
        spendable: Zatoshi?
    ): Boolean =
        spendable != null &&
            spendable.value < ready.quote.offer.quote.depositZat + (ready.quote.depositFeeZat ?: FEE_FALLBACK_ZAT)

    fun quote(
        ready: ConvertQuote.Ready,
        now: Long,
        currency: ConversionCurrency? = null,
    ): PrivateUsdQuoteState {
        val offer = ready.quote.offer
        val fee = ready.quote.depositFeeZat
        val secondsLeft = ready.secondsLeft(now).coerceAtLeast(0)
        return PrivateUsdQuoteState(
            pay = stringRes(Zatoshi(offer.quote.depositZat + (fee ?: 0))),
            networkFee = fee?.let { stringRes(Zatoshi(it)) },
            receive =
                if (token.isDollar) {
                    currency.format(offer.receives.toDecimal(token.decimals))
                } else {
                    tokenAmount(offer.receives, token, estimate = true)
                },
            fees = stringRes(R.string.convert_fees_value, currency.format(offer.relayerFee.toDecimal(token.decimals))),
            refreshesIn =
                stringRes(
                    R.string.convert_quote_expires,
                    "%d:%02d".format(secondsLeft / SECONDS_PER_MINUTE, secondsLeft % SECONDS_PER_MINUTE),
                ),
        )
    }

    private companion object {
        const val SECONDS_PER_MINUTE = 60

        // ZIP 317 for a typical deposit, until the wallet can price the real one.
        const val FEE_FALLBACK_ZAT = 15_000L
    }
}

internal fun PrivateUsdConvertTerms.info(phase: PrivateUsdConvertPhase): PrivateUsdInfo =
    when (phase) {
        PrivateUsdConvertPhase.AMOUNT -> {
            PrivateUsdInfo(
                title = stringRes(R.string.convert_info_title),
                steps =
                    listOf(
                        stringRes(R.string.convert_info_step_quote),
                        stringRes(R.string.convert_info_step_deposit),
                        stringRes(R.string.convert_info_step_claim),
                    ),
                notes =
                    listOf(
                        stringRes(R.string.convert_info_note_either),
                        stringRes(R.string.convert_info_note_time, duration),
                    ),
            )
        }

        PrivateUsdConvertPhase.REVIEW -> {
            PrivateUsdInfo(
                title = stringRes(R.string.convert_review_info_title),
                notes =
                    listOf(
                        stringRes(R.string.convert_review_info_pay),
                        stringRes(R.string.convert_review_info_receive),
                        stringRes(R.string.convert_review_info_quote),
                        stringRes(R.string.convert_info_note_either),
                        stringRes(R.string.convert_info_note_time, duration),
                    ),
            )
        }
    }
