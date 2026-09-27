// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd.convert

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapDeployment
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapQuote
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdToken
import co.electriccoin.zcash.ui.common.privateusd.toDecimal
import co.electriccoin.zcash.ui.common.privateusd.tokenAmount
import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.privateusd.about

internal data class ConvertForm(
    val phase: PrivateUsdConvertPhase = PrivateUsdConvertPhase.AMOUNT,
    /** An index into the presets, or one past them for "Other". */
    val selected: Int? = null,
    val custom: NumberTextFieldInnerState = NumberTextFieldInnerState(),
    val quote: ConvertQuote = ConvertQuote.None,
    val isConfirming: Boolean = false,
    val error: StringResource? = null,
)

internal sealed interface ConvertQuote {
    data object None : ConvertQuote

    data class Loading(
        val units: Int
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

/** A deployment's amounts, in dollars: what the presets are, what a custom amount may be, what a quote says. */
internal class PrivateUsdConvertTerms(
    deployment: AtomicSwapDeployment,
    private val token: PrivateUsdToken,
) {
    val presets: List<Int> = deployment.presetUnits
    val custom: Int = presets.size
    val duration: StringResource = deployment.expectedDuration.about()
    private val maxUnits = deployment.maxUnits
    private val unitDollars = deployment.unitBaseUnits.toBigInteger().toDecimal(token.decimals)

    val amounts: List<StringResource> =
        presets.map { stringRes("$" + (unitDollars * it.toBigDecimal()).stripTrailingZeros().toPlainString()) } +
            stringRes(R.string.convert_custom)

    val limits: StringResource = stringRes(R.string.convert_limits, unitDollars.toInt(), unitDollars.toInt() * maxUnits)

    /** Whole units within the limits, or null. */
    fun units(custom: NumberTextFieldInnerState): Int? {
        val (units, remainder) = custom.amount?.divideAndRemainder(unitDollars) ?: return null
        return units.toInt().takeIf { remainder.signum() == 0 && it in 1..maxUnits }
    }

    fun isShort(
        ready: ConvertQuote.Ready,
        spendable: Zatoshi?
    ): Boolean =
        spendable != null &&
            spendable.value < ready.quote.offer.quote.depositZat + (ready.quote.depositFeeZat ?: FEE_FALLBACK_ZAT)

    fun quote(
        ready: ConvertQuote.Ready,
        now: Long
    ): PrivateUsdQuoteState {
        val offer = ready.quote.offer
        val fee = ready.quote.depositFeeZat
        val secondsLeft = ready.secondsLeft(now).coerceAtLeast(0)
        return PrivateUsdQuoteState(
            pay = stringRes(Zatoshi(offer.quote.depositZat + (fee ?: 0))),
            networkFee = fee?.let { stringRes(Zatoshi(it)) },
            receive = tokenAmount(offer.receives, token, estimate = true),
            fees = stringRes(R.string.convert_fees_value, tokenAmount(offer.relayerFee, token, estimate = true)),
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
