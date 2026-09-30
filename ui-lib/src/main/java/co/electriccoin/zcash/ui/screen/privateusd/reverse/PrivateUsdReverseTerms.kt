// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd.reverse

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapDeployment
import co.electriccoin.zcash.ui.common.atomicswap.ReverseSwapState
import co.electriccoin.zcash.ui.common.atomicswap.ZIP317_MIN_FEE_ZAT
import co.electriccoin.zcash.ui.common.privateusd.LocalCurrency
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdBalanceState
import co.electriccoin.zcash.ui.common.privateusd.privateUsdToken
import co.electriccoin.zcash.ui.common.privateusd.toBaseUnits
import co.electriccoin.zcash.ui.common.privateusd.toDecimal
import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.privateusd.PrivateUsdInfo
import co.electriccoin.zcash.ui.screen.privateusd.QUOTE_EXPIRY_MARGIN_SECONDS
import co.electriccoin.zcash.ui.screen.privateusd.availableText
import co.electriccoin.zcash.ui.screen.privateusd.isPositive
import xyz.justzappit.offramp.atomicswap.ReversePhase
import xyz.justzappit.offramp.atomicswap.ReverseSwapRecord
import xyz.justzappit.offramp.p2p.Usdc6
import java.math.BigDecimal
import java.math.BigInteger

internal data class ReverseForm(
    val amount: NumberTextFieldInnerState = NumberTextFieldInnerState(),
    /** What [amount] is in, fixed from the first digit so a rate arriving later can't change what it means. */
    val currency: LocalCurrency? = null,
    val requested: Usdc6? = null,
    val step: ReverseStep = ReverseStep.Typing,
    /** A finished conversion kept out of sight while a new one is set up. */
    val hidden: Int? = null,
    /** An amount no fresh quote came for; it's asked for again once the amount changes. */
    val stale: Usdc6? = null,
    val error: StringResource? = null,
) {
    val isActing: Boolean get() = step is ReverseStep.Acting

    /** A step the user starts can run now: none is running, and no quote is loading. */
    val canAct: Boolean get() = !isActing && step != ReverseStep.Quoting

    /** Back waits only for a step the user authorized. */
    val isBackEnabled: Boolean get() = (step as? ReverseStep.Acting)?.kind != ReverseStepKind.AUTHORIZED

    /** On the review, or running the conversion it authorized. */
    val isReviewing: Boolean
        get() = step == ReverseStep.Reviewing || (step as? ReverseStep.Acting)?.from == ReverseStep.Reviewing

    /** The kept conversion to show: not a hidden one, and a quote only for the amount it was for. */
    fun shown(record: ReverseSwapRecord?): ReverseSwapRecord? =
        record?.takeUnless {
            it.index == hidden || (it.phase == ReversePhase.QUOTED && it.quote.terms.amount != requested)
        }

    /** What paying takes from the private balance: the quote's debit, or the typed amount before there's one. */
    fun debit(record: ReverseSwapRecord?): BigInteger? = (record?.cost?.debit ?: requested)?.micros

    /** More than [available] would leave the private balance, before the payment has gone. */
    fun isShort(
        record: ReverseSwapRecord?,
        available: BigInteger?
    ): Boolean {
        val debit = debit(record) ?: return false
        return record?.funding == null && available != null && debit > available
    }

    fun canPay(
        record: ReverseSwapRecord?,
        available: BigInteger?,
        isExpired: Boolean,
    ): Boolean = available != null && debit(record) != null && !isShort(record, available) && !isExpired

    /** Too much, or an amount no conversion can be; a zero still being typed isn't flagged. */
    fun isInvalid(
        record: ReverseSwapRecord?,
        available: BigInteger?
    ): Boolean = isShort(record, available) || (amount.isPositive && requested == null)

    fun canSwitchDirection(record: ReverseSwapRecord?): Boolean = !isActing && record?.underWay != true

    /** Why it can't go on, most pressing first. */
    fun message(
        record: ReverseSwapRecord?,
        available: BigInteger?,
        isExpired: Boolean,
    ): StringResource? =
        error
            ?: stringRes(R.string.reverse_insufficient).takeIf { isShort(record, available) }
            ?: stringRes(R.string.convert_quote_ran_out).takeIf { isExpired && isReviewing }
}

internal sealed interface ReverseStep {
    data object Typing : ReverseStep

    data object Quoting : ReverseStep

    data object Reviewing : ReverseStep

    /** A step the user started; the form goes back to [from] after it. */
    data class Acting(
        val from: ReverseStep,
        val kind: ReverseStepKind,
    ) : ReverseStep
}

/** A look-up, which back cancels, or a step the user authorized with the app lock, which back waits for. */
internal enum class ReverseStepKind { LOOK_UP, AUTHORIZED }

internal data class ReverseConversion(
    val swap: ReverseSwapState,
    /** The latest refunded conversion whose payout can be recovered now, if any. */
    val rescuable: Int?,
    /** Whether the quote shown has run out. */
    val isExpired: Boolean,
)

/** Whether [record] is a quote that has run out, [now] in epoch seconds. */
internal fun isExpiredQuote(
    record: ReverseSwapRecord?,
    now: Long
): Boolean = record?.phase == ReversePhase.QUOTED && record.quote.terms.expiresAt <= now + QUOTE_EXPIRY_MARGIN_SECONDS

/** What reverse conversions take, cost and bring, in the user's currency. */
internal class PrivateUsdReverseTerms(
    deployment: AtomicSwapDeployment,
) {
    private val token = deployment.privateUsdToken
    private val least = deployment.minAmount.micros
    private val most = deployment.maxAmount.micros
    private val maxRefundFee = deployment.swap.maxRelayerFee.micros

    /** [typed] of [currency] in the token, when a conversion can be that much. */
    fun amount(
        typed: BigDecimal,
        currency: LocalCurrency
    ): Usdc6? =
        typed
            .takeIf { currency.holds(it) }
            ?.let { currency.toDollars(it).toBaseUnits(token.decimals) }
            ?.takeIf { it in least..most }
            ?.let(::Usdc6)

    fun limits(currency: LocalCurrency): StringResource =
        stringRes(
            R.string.convert_local_limits,
            currency.formatAtLeast(least.toDecimal(token.decimals)),
            currency.formatAtMost(most.toDecimal(token.decimals)),
        )

    fun spendable(balance: PrivateUsdBalanceState): BigInteger? = balance.available(token)

    fun available(
        balance: PrivateUsdBalanceState,
        currency: LocalCurrency
    ): StringResource = balance.availableText(token, currency)

    /** As much as [units] pay for, up to the most a conversion takes. */
    fun maximum(
        units: BigInteger,
        currency: LocalCurrency
    ): NumberTextFieldInnerState = currency.maximumField(units.min(most).toDecimal(token.decimals))

    fun format(
        amount: Usdc6,
        currency: LocalCurrency
    ): StringResource = currency.format(amount.micros.toDecimal(token.decimals))

    fun info(currency: LocalCurrency) =
        PrivateUsdInfo(
            title = stringRes(R.string.reverse_title),
            titleDescription = stringRes(R.string.reverse_title_description),
            steps = listOf(stringRes(R.string.reverse_intro)),
            notes =
                listOf(
                    stringRes(R.string.reverse_gas_account),
                    stringRes(R.string.reverse_refund_terms, currency.format(maxRefundFee.toDecimal(token.decimals))),
                ),
        )
}

/** The ZEC the sweep home brings: its own figure once there is one, the deposit less the least fee before. */
internal fun ReverseSwapRecord.receivedZat(): Long =
    receive?.receivedZat
        ?: receiveEstimate?.receivedZat
        ?: (quote.terms.depositZat - ZIP317_MIN_FEE_ZAT).coerceAtLeast(0)
