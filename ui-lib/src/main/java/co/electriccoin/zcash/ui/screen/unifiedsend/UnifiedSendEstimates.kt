package co.electriccoin.zcash.ui.screen.unifiedsend

import androidx.compose.ui.text.TextRange
import co.electriccoin.zcash.ui.design.component.InnerTextFieldState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
import co.electriccoin.zcash.ui.design.component.TextSelection
import co.electriccoin.zcash.ui.design.util.stringResByDynamicNumber
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

// Client-side conversions between the two sides of a cross-chain payment: value one leg in USD,
// then divide by the other leg's price. Nothing here is binding — the numbers the user commits to
// come from the NEAR quote. These only drive what the non-authoritative half of the form displays,
// and the pre-quote balance check. Ported from upstream's ExactOutputVMMapper.

/** Estimated ZEC needed to deliver [token] of the destination asset. */
internal fun estimateZecFromToken(
    token: BigDecimal?,
    tokenUsdPrice: BigDecimal?,
    zecUsdPrice: BigDecimal?
): BigDecimal? = divideByPrice(usdValue(token, tokenUsdPrice), zecUsdPrice)

/** Estimated destination-asset amount [zec] buys — the "They receive ≈" figure. */
internal fun estimateTokenFromZec(
    zec: BigDecimal?,
    zecUsdPrice: BigDecimal?,
    tokenUsdPrice: BigDecimal?
): BigDecimal? = divideByPrice(usdValue(zec, zecUsdPrice), tokenUsdPrice)

/** USD value of [token] of the destination asset — upstream's `getOriginFiatAmount`. */
internal fun estimateUsdFromToken(token: BigDecimal?, tokenUsdPrice: BigDecimal?): BigDecimal? =
    usdValue(token, tokenUsdPrice)

/** The destination amount [usd] buys, the inverse of [estimateUsdFromToken]. */
internal fun estimateTokenFromUsd(usd: BigDecimal?, tokenUsdPrice: BigDecimal?): BigDecimal? =
    divideByPrice(usd, tokenUsdPrice)

private fun usdValue(amount: BigDecimal?, usdPrice: BigDecimal?): BigDecimal? =
    if (amount == null || usdPrice == null) {
        null
    } else {
        amount.multiply(usdPrice, MathContext.DECIMAL128)
    }

/** Null for a missing or non-positive price — there is no sane conversion to offer then. */
private fun divideByPrice(usd: BigDecimal?, price: BigDecimal?): BigDecimal? =
    if (usd == null || price == null || price.signum() <= 0) {
        null
    } else {
        usd.divide(price, MathContext.DECIMAL128)
    }

/**
 * Truncates to the destination asset's on-chain precision. NEAR truncates the same way when it
 * builds the quote request, so anything finer than this can never reach the recipient.
 */
internal fun BigDecimal.truncateToAssetDecimals(decimals: Int): BigDecimal =
    if (scale() <= decimals) this else setScale(decimals, RoundingMode.DOWN)

/**
 * True when a typed amount carries more precision than the asset can settle. Used to drop the
 * offending keystroke rather than silently re-writing what the user sees.
 */
internal fun NumberTextFieldInnerState.exceedsAssetDecimals(decimals: Int): Boolean {
    val typed = amount ?: return false
    return typed.stripTrailingZeros().scale() > decimals
}

/**
 * Builds a field state from a computed amount, for the half of a linked pair the user is not
 * currently typing into. [selection] decides what happens when they do reach for it: [TextSelection.End]
 * to carry on from the figure (how the derived half of an active pair behaves), or a range covering
 * the whole value so the first keystroke replaces an estimate the user never typed.
 */
internal fun BigDecimal?.toAmountState(
    selection: TextSelection = TextSelection.End
): NumberTextFieldInnerState =
    this?.let { amount ->
        NumberTextFieldInnerState(
            innerTextFieldState =
                InnerTextFieldState(
                    value = stringResByDynamicNumber(amount, includeGroupingSeparator = false),
                    selection = selection,
                ),
            amount = amount,
            lastValidAmount = amount,
        )
    } ?: NumberTextFieldInnerState()

/**
 * Selects the whole value, so a field holding a figure the user did not type is replaced rather
 * than appended to on the first keystroke. The end is clamped to the rendered text by
 * `ZashiNumberTextField`, so it does not need to be known here.
 */
internal val SELECT_ALL = TextSelection.ByTextRange(TextRange(0, Int.MAX_VALUE))
