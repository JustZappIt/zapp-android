package co.electriccoin.zcash.ui.screen.invest.sell

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.invest.model.InvestAsset
import co.electriccoin.zcash.ui.common.invest.model.PreparedSell
import co.electriccoin.zcash.ui.common.invest.model.SellEstimate
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.invest.buy.InvestBuyPresenter
import co.electriccoin.zcash.ui.screen.invest.common.InvestCurrency
import co.electriccoin.zcash.ui.screen.invest.common.InvestFormat
import java.math.BigDecimal
import java.math.MathContext

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
            youGet = priced?.let { stringRes(R.string.invest_sell_you_get_value, InvestFormat.zec(it.zecOut)) },
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

    fun noPriceBody(asset: InvestAsset): StringResource = stringRes(R.string.invest_sell_no_price_body, asset.name)

    fun review(
        prepared: PreparedSell,
        remainingSeconds: Long,
        money: InvestCurrency,
    ): SellReviewFigures =
        SellReviewFigures(
            authorisation =
                stringRes(
                    R.string.invest_sell_authorise,
                    InvestFormat.units(prepared.units, prepared.asset.ticker),
                    money.format(prepared.usdIn),
                ),
            atLeast = stringRes(InvestFormat.zec(prepared.zecOutMin)),
            expected = stringRes(InvestFormat.zec(prepared.zecOutExpected)),
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
