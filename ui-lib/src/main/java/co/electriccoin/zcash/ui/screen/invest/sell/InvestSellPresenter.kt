package co.electriccoin.zcash.ui.screen.invest.sell

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.invest.model.Holding
import co.electriccoin.zcash.ui.common.invest.model.InvestAsset
import co.electriccoin.zcash.ui.common.invest.model.PreparedSell
import co.electriccoin.zcash.ui.common.invest.model.SellEstimate
import co.electriccoin.zcash.ui.common.invest.repository.InvestRepository
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.invest.buy.InvestBuyPresenter
import co.electriccoin.zcash.ui.screen.invest.common.InvestCurrency
import co.electriccoin.zcash.ui.screen.invest.common.InvestFormat
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/** The dry sell quote's state on the amount screen. */
internal sealed interface SellQuote {
    data object Idle : SellQuote

    data object Loading : SellQuote

    data class Ready(
        val estimate: SellEstimate,
    ) : SellQuote

    data class Failed(
        val message: StringResource,
    ) : SellQuote
}

/** Turns sell quotes into what I8 and I9 show; no state of its own. */
internal object InvestSellPresenter {
    fun ledger(
        quote: SellQuote,
        asset: InvestAsset,
        money: InvestCurrency,
    ): InvestSellLedger? {
        val estimate = (quote as? SellQuote.Ready)?.estimate
        if (estimate is SellEstimate.NoPrice) return null
        val priced = estimate as? SellEstimate.Priced
        return InvestSellLedger(
            youSell =
                priced?.let {
                    stringRes(
                        R.string.invest_buy_you_get_value_exact,
                        money.format(it.usdIn),
                        InvestFormat.units(it.unitsIn, asset.ticker),
                    )
                },
            youGet =
                priced?.let {
                    stringRes(R.string.invest_sell_zec_with_value, InvestFormat.zec(it.zecOut), money.format(it.usdOut))
                },
            fees = priced?.let { stringRes(money.format(it.feesUsd)) },
            feeNote = priced?.let(::feeNote),
            eta =
                priced
                    ?.etaSeconds
                    ?.let(InvestFormat::etaMinutes)
                    ?.let { stringRes(R.string.invest_buy_eta_value, it) },
        )
    }

    /**
     * The fixed ZEC withdrawal fee, named; above [HIGH_FEE_SHARE] of the sale it also suggests selling more at once,
     * because the fee doesn't grow with the sale.
     */
    private fun feeNote(priced: SellEstimate.Priced): StringResource? {
        val feeZec = priced.withdrawFeeZec?.takeIf { it.signum() > 0 } ?: return null
        val zecUsd =
            priced.zecOut
                .takeIf { it.signum() > 0 }
                ?.let { priced.usdOut.divide(it, MathContext.DECIMAL64) }
        val feeShare =
            zecUsd?.let { feeZec.multiply(it).divide(priced.usdIn.max(BigDecimal.ONE), MathContext.DECIMAL64) }
        return if (feeShare != null && feeShare > HIGH_FEE_SHARE) {
            stringRes(R.string.invest_sell_fee_note_high, InvestFormat.zec(feeZec))
        } else {
            stringRes(R.string.invest_sell_fee_note, InvestFormat.zec(feeZec))
        }
    }

    fun notice(
        quote: SellQuote,
        asset: InvestAsset,
        money: InvestCurrency,
    ): Pair<StringResource?, Boolean> =
        when (quote) {
            SellQuote.Idle -> {
                null to false
            }

            SellQuote.Loading -> {
                stringRes(R.string.invest_buy_quote_loading) to false
            }

            is SellQuote.Failed -> {
                quote.message to true
            }

            is SellQuote.Ready -> {
                when (val estimate = quote.estimate) {
                    is SellEstimate.BelowMinimum -> {
                        val minimum = money.formatPreset(money.presetFromUsd(estimate.minimumUsd))
                        stringRes(R.string.invest_buy_below_minimum, minimum) to false
                    }

                    is SellEstimate.ExceedsHolding -> {
                        stringRes(R.string.invest_sell_exceeds, InvestFormat.units(estimate.heldUnits, asset.ticker)) to
                            true
                    }

                    SellEstimate.TooSmallToSell -> {
                        stringRes(R.string.invest_sell_too_small) to true
                    }

                    SellEstimate.NothingHeld -> {
                        stringRes(R.string.invest_sell_nothing_held, asset.name) to true
                    }

                    is SellEstimate.Priced, SellEstimate.NoPrice -> {
                        null to false
                    }
                }
            }
        }

    /** "You hold": value first, then shares; shares alone without a price. */
    fun holdingText(
        held: Holding?,
        asset: InvestAsset,
        money: InvestCurrency,
    ): StringResource {
        val units = InvestFormat.units(held?.units ?: BigDecimal.ZERO, asset.ticker)
        val value = held?.usdValue ?: return stringRes(units)
        return stringRes(R.string.invest_buy_you_get_value_exact, money.format(value), units)
    }

    /**
     * "This would leave less than $40. Sell all instead?", only for a partial sale that would leave less than the
     * minimum behind: selling that remainder later would cost more in fixed fees than it is worth.
     */
    fun sellAllSuggestion(
        estimate: SellEstimate?,
        held: Holding?,
        sellAll: Boolean,
        money: InvestCurrency,
    ): StringResource? {
        val usdIn = (estimate as? SellEstimate.Priced)?.usdIn
        val left = held?.usdValue?.let { value -> usdIn?.let { value.subtract(it) } }
        val leavesTooLittle = left != null && left.signum() > 0 && left < InvestRepository.MINIMUM_USD
        return if (!sellAll && leavesTooLittle) {
            stringRes(
                R.string.invest_sell_all_suggestion,
                money.formatPreset(money.presetFromUsd(InvestRepository.MINIMUM_USD)),
            )
        } else {
            null
        }
    }

    /**
     * [fraction] of the holding as the field shows it: shares to four places, or money to the cent, rounded down
     * so it never asks for more than is held. Null when there is nothing (or no price, in money) to take it from.
     */
    fun share(
        held: Holding?,
        mode: SellAmountMode,
        money: InvestCurrency,
        fraction: BigDecimal,
    ): BigDecimal? =
        when (mode) {
            SellAmountMode.SHARES -> {
                held?.units?.multiply(fraction)?.setScale(UNITS_SCALE, RoundingMode.DOWN)
            }

            SellAmountMode.MONEY -> {
                held?.usdValue?.let { money.fromUsd(it.multiply(fraction)) }?.setScale(2, RoundingMode.DOWN)
            }
        }

    val HALF: BigDecimal = BigDecimal("0.5")
    private const val UNITS_SCALE = 4

    fun noPriceBody(asset: InvestAsset): StringResource = stringRes(R.string.invest_sell_no_price_body, asset.name)

    fun review(
        prepared: PreparedSell,
        remainingSeconds: Long,
        money: InvestCurrency,
        /** 1Click's USD price of ZEC, to value the payout in the user's currency; ZEC alone without it. */
        zecPrice: BigDecimal?,
    ): SellReviewFigures =
        SellReviewFigures(
            authorisation =
                stringRes(
                    R.string.invest_sell_authorise,
                    InvestFormat.units(prepared.units, prepared.asset.ticker),
                    money.format(prepared.usdIn),
                ),
            atLeast =
                zecPrice?.let {
                    // The ZEC is the guaranteed floor; its value in the user's currency moves with the price.
                    stringRes(
                        R.string.invest_sell_zec_at_least_with_value,
                        InvestFormat.zec(prepared.zecOutMin),
                        money.format(prepared.zecOutMin.multiply(it)),
                    )
                } ?: stringRes(InvestFormat.zec(prepared.zecOutMin)),
            expected =
                zecPrice?.let {
                    stringRes(
                        R.string.invest_sell_zec_with_value,
                        InvestFormat.zec(prepared.zecOutExpected),
                        money.format(prepared.zecOutExpected.multiply(it)),
                    )
                } ?: stringRes(R.string.invest_sell_you_get_value, InvestFormat.zec(prepared.zecOutExpected)),
            fees = stringRes(money.format(prepared.feesUsd)),
            countdown =
                if (remainingSeconds > 0) {
                    stringRes(InvestBuyPresenter.countdownText(remainingSeconds))
                } else {
                    stringRes(R.string.invest_review_expired)
                },
        )

    private val HIGH_FEE_SHARE = BigDecimal("0.02")
}

internal data class SellReviewFigures(
    val authorisation: StringResource,
    val atLeast: StringResource,
    val expected: StringResource,
    val fees: StringResource,
    val countdown: StringResource,
)
