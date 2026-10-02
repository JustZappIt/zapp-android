package co.electriccoin.zcash.ui.screen.invest.buy

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.invest.model.BuyEstimate
import co.electriccoin.zcash.ui.common.invest.model.InvestAsset
import co.electriccoin.zcash.ui.common.invest.model.PreparedBuy
import co.electriccoin.zcash.ui.common.invest.model.TradingSchedule
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.invest.common.InvestCurrency
import co.electriccoin.zcash.ui.screen.invest.common.InvestFormat
import co.electriccoin.zcash.ui.screen.invest.common.UsMarketHours
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.Instant

/** The dry quote's state on the amount screen. */
internal sealed interface BuyQuote {
    /** No amount entered. */
    data object Idle : BuyQuote

    /** Waiting for typing to stop, or for 1Click to answer. */
    data object Loading : BuyQuote

    data class Ready(
        val estimate: BuyEstimate,
    ) : BuyQuote

    data class Failed(
        val message: StringResource,
    ) : BuyQuote
}

/** Turns quotes into the words and figures I5 and I6 show; no state of its own. */
internal object InvestBuyPresenter {
    fun ledger(
        quote: BuyQuote,
        asset: InvestAsset,
        amountUsd: BigDecimal?,
        money: InvestCurrency,
    ): InvestBuyLedger? {
        val estimate = (quote as? BuyQuote.Ready)?.estimate
        if (estimate is BuyEstimate.NoPrice) return null
        val priced = estimate as? BuyEstimate.Priced
        return InvestBuyLedger(
            youSend = priced?.let { stringRes(InvestFormat.zec(it.zecIn)) },
            youGet =
                priced?.let {
                    stringRes(
                        R.string.invest_buy_you_get_value,
                        money.format(it.usdOut),
                        InvestFormat.units(it.unitsOut, asset.ticker),
                    )
                },
            fees = priced?.let { feesText(it.feesUsd, amountUsd, money) },
            eta =
                priced
                    ?.etaSeconds
                    ?.let(InvestFormat::etaMinutes)
                    ?.let { stringRes(R.string.invest_buy_eta_value, it) },
        )
    }

    fun notice(
        quote: BuyQuote,
        money: InvestCurrency,
    ): Pair<StringResource?, Boolean> =
        when (quote) {
            BuyQuote.Idle -> {
                null to false
            }

            BuyQuote.Loading -> {
                stringRes(R.string.invest_buy_quote_loading) to false
            }

            is BuyQuote.Failed -> {
                quote.message to true
            }

            is BuyQuote.Ready -> {
                when (val estimate = quote.estimate) {
                    is BuyEstimate.BelowMinimum -> {
                        val minimum = money.formatPreset(money.presetFromUsd(estimate.minimumUsd))
                        stringRes(R.string.invest_buy_below_minimum, minimum) to false
                    }

                    is BuyEstimate.InsufficientZec -> {
                        stringRes(R.string.invest_buy_insufficient, InvestFormat.zec(estimate.spendableZec)) to true
                    }

                    is BuyEstimate.Priced, BuyEstimate.NoPrice -> {
                        null to false
                    }
                }
            }
        }

    fun noPriceBody(asset: InvestAsset): StringResource = stringRes(R.string.invest_buy_no_price_body, asset.name)

    /** For a weekday stock outside Ondo's 24/5 window, when it reopens in the phone's time. */
    fun reopen(asset: InvestAsset, now: Instant): StringResource? {
        if (asset.schedule != TradingSchedule.WEEKDAYS || UsMarketHours.isWeekdayWindowOpen(now)) return null
        return stringRes(
            R.string.invest_buy_no_price_reopen,
            InvestFormat.localDayTime(UsMarketHours.nextWeekdayWindowOpen(now)),
        )
    }

    fun review(
        prepared: PreparedBuy,
        remainingSeconds: Long,
        money: InvestCurrency,
    ): ReviewFigures {
        val ticker = prepared.asset.ticker
        // The least the buy can deliver, valued at the quote's own price per unit.
        val atLeastUsd =
            if (prepared.unitsOutExpected.signum() > 0) {
                prepared.usdOut
                    .multiply(prepared.unitsOutMin)
                    .divide(prepared.unitsOutExpected, MathContext.DECIMAL64)
            } else {
                BigDecimal.ZERO
            }
        return ReviewFigures(
            youSend = stringRes(InvestFormat.zec(prepared.zecIn)),
            atLeast =
                stringRes(
                    R.string.invest_buy_you_get_value_exact,
                    money.formatAtLeast(atLeastUsd),
                    InvestFormat.units(prepared.unitsOutMin, ticker),
                ),
            expected =
                stringRes(
                    R.string.invest_buy_you_get_value_exact,
                    money.format(prepared.usdOut),
                    InvestFormat.units(prepared.unitsOutExpected, ticker),
                ),
            fees = stringRes(money.format(prepared.feesUsd)),
            countdown =
                if (remainingSeconds > 0) {
                    stringRes(countdownText(remainingSeconds))
                } else {
                    stringRes(R.string.invest_review_expired)
                },
            privacy =
                prepared.refundFeeZec?.let { stringRes(R.string.invest_review_privacy, InvestFormat.zec(it)) }
                    ?: stringRes(R.string.invest_review_privacy_no_fee),
        )
    }

    /** "9:42": minutes and seconds left on the held price. */
    fun countdownText(seconds: Long): String =
        "%d:%02d".format(seconds / SECONDS_PER_MINUTE, seconds % SECONDS_PER_MINUTE)

    private fun feesText(
        feesUsd: BigDecimal,
        amountUsd: BigDecimal?,
        money: InvestCurrency,
    ): StringResource {
        val percent =
            amountUsd
                ?.takeIf { it.signum() > 0 }
                ?.let { feesUsd.multiply(HUNDRED).divide(it, MathContext.DECIMAL64) }
        return if (percent == null) {
            stringRes(money.format(feesUsd))
        } else {
            stringRes(R.string.invest_buy_fee_value, money.format(feesUsd), InvestFormat.percent(percent))
        }
    }

    private val HUNDRED = BigDecimal.TEN.pow(2)
    private const val SECONDS_PER_MINUTE = 60L
}

internal data class ReviewFigures(
    val youSend: StringResource,
    val atLeast: StringResource,
    val expected: StringResource,
    val fees: StringResource,
    val countdown: StringResource,
    val privacy: StringResource,
)
