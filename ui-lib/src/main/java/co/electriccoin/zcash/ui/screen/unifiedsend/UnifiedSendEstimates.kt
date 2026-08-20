package co.electriccoin.zcash.ui.screen.unifiedsend

import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
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
 * Whether the field is empty. The destination field is authoritative as soon as it holds anything;
 * emptying it hands authority back to the ZEC side. Mid-typing values like "0." parse to a null
 * amount while still being input, so the text — not the parsed amount — decides.
 */
internal fun NumberTextFieldInnerState.isBlankInput(): Boolean = innerTextFieldState.value.isEmpty()
