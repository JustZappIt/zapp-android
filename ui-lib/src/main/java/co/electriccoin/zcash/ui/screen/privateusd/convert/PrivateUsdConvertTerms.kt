// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd.convert

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapDeployment
import co.electriccoin.zcash.ui.common.privateusd.LocalCurrency
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdBalanceState
import co.electriccoin.zcash.ui.common.privateusd.basisPoints
import co.electriccoin.zcash.ui.common.privateusd.privateUsdToken
import co.electriccoin.zcash.ui.common.privateusd.toDecimal
import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.privateusd.PrivateUsdInfo
import co.electriccoin.zcash.ui.screen.privateusd.QUOTE_EXPIRY_MARGIN_SECONDS
import co.electriccoin.zcash.ui.screen.privateusd.about
import co.electriccoin.zcash.ui.screen.privateusd.availableText
import co.electriccoin.zcash.ui.screen.privateusd.zatoshi
import co.electriccoin.zcash.ui.screen.privateusd.zecField
import xyz.justzappit.offramp.atomicswap.RAILGUN_FEE

internal data class ConvertForm(
    val phase: PrivateUsdConvertPhase = PrivateUsdConvertPhase.AMOUNT,
    val amount: NumberTextFieldInnerState = NumberTextFieldInnerState(),
    val quote: ConvertQuote = ConvertQuote.None,
    val isConfirming: Boolean = false,
    val error: StringResource? = null,
) {
    val ready: ConvertQuote.Ready? get() = quote as? ConvertQuote.Ready

    /** More than [spendable], which is said before any quote is asked for. */
    fun isShort(spendable: Zatoshi?): Boolean {
        val totalZat = amount.zatoshi()
        return totalZat != null && spendable != null && totalZat > spendable.value
    }

    fun isExpired(now: Long): Boolean = ready?.let { it.secondsLeft(now) <= 0 } == true

    fun canGoOn(
        spendable: Zatoshi?,
        now: Long
    ): Boolean = ready != null && !isExpired(now) && spendable != null && !isShort(spendable)

    /** Why it can't go on, most pressing first. */
    fun message(
        spendable: Zatoshi?,
        now: Long
    ): StringResource? =
        when {
            error != null -> error
            spendable != null && isShort(spendable) -> stringRes(R.string.convert_insufficient, stringRes(spendable))
            quote is ConvertQuote.Failed -> quote.message
            phase == PrivateUsdConvertPhase.REVIEW && isExpired(now) -> stringRes(R.string.convert_quote_ran_out)
            else -> null
        }

    /** [next] where it still applies: a quote landing once the review is up is dropped. */
    fun withQuote(
        next: ConvertQuote,
        fillsAmount: Boolean
    ): ConvertForm =
        when {
            phase != PrivateUsdConvertPhase.AMOUNT -> this
            fillsAmount && next is ConvertQuote.Ready -> copy(amount = zecField(next.quote.totalZat), quote = next)
            else -> copy(quote = next)
        }
}

internal sealed interface ConvertQuote {
    data object None : ConvertQuote

    data object Loading : ConvertQuote

    data class Ready(
        val quote: ZecQuote
    ) : ConvertQuote {
        fun secondsLeft(now: Long) = quote.offer.quote.expiresAt - QUOTE_EXPIRY_MARGIN_SECONDS - now

        // One run out on arrival would be asked for again every second: the device's clock is likely ahead.
        fun arrived(now: Long): ConvertQuote =
            if (secondsLeft(now) > 0) this else Failed(stringRes(R.string.convert_quote_clock_ahead))
    }

    data class Failed(
        val message: StringResource
    ) : ConvertQuote
}

/** What the screen shows beside the amount: the ZEC there is to spend, and the private dollars in their currency. */
internal data class ConvertHoldings(
    val spendable: Zatoshi?,
    val balance: PrivateUsdBalanceState,
    val currency: LocalCurrency,
)

/** What a deployment's conversions cost and bring, in the user's currency. */
internal class PrivateUsdConvertTerms(
    deployment: AtomicSwapDeployment,
) {
    private val token = deployment.privateUsdToken
    private val duration: StringResource = deployment.expectedDuration.about()

    fun available(holdings: ConvertHoldings): StringResource = holdings.balance.availableText(token, holdings.currency)

    fun estimate(
        quote: ZecQuote,
        currency: LocalCurrency
    ): NumberTextFieldInnerState {
        val receives = quote.offer.receives
        return currency.field(receives.micros.toDecimal(token.decimals))
    }

    fun quote(
        ready: ConvertQuote.Ready,
        phase: PrivateUsdConvertPhase,
        now: Long,
        currency: LocalCurrency,
    ): PrivateUsdQuoteState {
        val offer = ready.quote.offer
        val secondsLeft = ready.secondsLeft(now).coerceAtLeast(0)
        return PrivateUsdQuoteState(
            pay = stringRes(Zatoshi(ready.quote.totalZat)),
            networkFee = stringRes(Zatoshi(ready.quote.feeZat)),
            receive = currency.format(offer.receives.micros.toDecimal(token.decimals)),
            fees =
                stringRes(
                    R.string.convert_fees_value,
                    currency.format(offer.relayerFee.micros.toDecimal(token.decimals)),
                    basisPoints(RAILGUN_FEE),
                ),
            expiry =
                stringRes(
                    when (phase) {
                        PrivateUsdConvertPhase.AMOUNT -> R.string.convert_quote_expires
                        PrivateUsdConvertPhase.REVIEW -> R.string.convert_quote_holds
                    },
                    stringRes(
                        R.string.convert_quote_countdown,
                        secondsLeft / SECONDS_PER_MINUTE,
                        secondsLeft % SECONDS_PER_MINUTE,
                    ),
                ),
        )
    }

    fun info(phase: PrivateUsdConvertPhase): PrivateUsdInfo =
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
                            stringRes(R.string.convert_review_info_receive, basisPoints(RAILGUN_FEE)),
                            stringRes(R.string.convert_review_info_quote),
                            stringRes(R.string.convert_info_note_either),
                            stringRes(R.string.convert_info_note_time, duration),
                        ),
                )
            }
        }

    private companion object {
        const val SECONDS_PER_MINUTE = 60
    }
}
