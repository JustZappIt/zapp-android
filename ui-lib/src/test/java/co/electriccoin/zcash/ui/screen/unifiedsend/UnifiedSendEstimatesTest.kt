package co.electriccoin.zcash.ui.screen.unifiedsend

import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
import co.electriccoin.zcash.ui.design.util.stringRes
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The client-side conversions behind "You pay ≈ X" and "They receive ≈ Y", and the precision rules
 * that keep a typed destination amount inside what the destination chain can actually settle.
 */
class UnifiedSendEstimatesTest {
    // region estimateZecFromToken — the pre-quote cost of an exact-output payment

    @Test
    fun `zec estimate values the recipient amount in USD and divides by the ZEC price`() {
        // 0.001 BTC at $50,000 == $50; at $50/ZEC that is 1 ZEC.
        assertEquals(
            0,
            BigDecimal.ONE.compareTo(
                estimateZecFromToken(
                    token = BigDecimal("0.001"),
                    tokenUsdPrice = BigDecimal("50000"),
                    zecUsdPrice = BigDecimal("50")
                )
            )
        )
    }

    @Test
    fun `zec estimate is unavailable while any price is missing`() {
        assertNull(estimateZecFromToken(BigDecimal.ONE, null, BigDecimal("50")))
        assertNull(estimateZecFromToken(BigDecimal.ONE, BigDecimal("50000"), null))
        assertNull(estimateZecFromToken(null, BigDecimal("50000"), BigDecimal("50")))
    }

    @Test
    fun `zec estimate refuses a zero ZEC price rather than dividing by it`() {
        assertNull(estimateZecFromToken(BigDecimal.ONE, BigDecimal("50000"), BigDecimal.ZERO))
    }

    // endregion

    // region estimateTokenFromZec — the "They receive ≈" figure

    @Test
    fun `token estimate is the inverse of the zec estimate`() {
        val token =
            estimateTokenFromZec(
                zec = BigDecimal.ONE,
                zecUsdPrice = BigDecimal("50"),
                tokenUsdPrice = BigDecimal("50000")
            )
        assertEquals(0, BigDecimal("0.001").compareTo(token))
    }

    @Test
    fun `token estimate refuses a zero token price`() {
        assertNull(estimateTokenFromZec(BigDecimal.ONE, BigDecimal("50"), BigDecimal.ZERO))
    }

    // endregion

    // region estimateUsdFromToken — the figure the slippage sheet quotes against

    @Test
    fun `usd value of the recipient amount is token times price`() {
        assertEquals(
            0,
            BigDecimal("50").compareTo(estimateUsdFromToken(BigDecimal("0.001"), BigDecimal("50000")))
        )
    }

    @Test
    fun `usd value is unavailable without a price`() {
        assertNull(estimateUsdFromToken(BigDecimal("0.001"), null))
    }

    // endregion

    // region precision — the destination chain decides how fine an amount can be

    @Test
    fun `truncation drops precision the asset cannot settle and rounds down`() {
        // USDC has 6 decimals; the 7th digit cannot reach the recipient, and rounding up would
        // ask for more than the user typed.
        assertEquals(BigDecimal("1.234567"), BigDecimal("1.2345679").truncateToAssetDecimals(6))
    }

    @Test
    fun `truncation leaves an amount already inside the asset's precision alone`() {
        val amount = BigDecimal("1.5")
        assertEquals(amount, amount.truncateToAssetDecimals(8))
    }

    @Test
    fun `an amount finer than the asset can settle is flagged`() {
        assertTrue(amountState("1.2345679").exceedsAssetDecimals(6))
        assertFalse(amountState("1.234567").exceedsAssetDecimals(6))
    }

    @Test
    fun `trailing zeros are not treated as precision`() {
        // "1.500000000" is 1.5 — typing it against a 2-decimal asset must not be rejected.
        assertFalse(amountState("1.500000000").exceedsAssetDecimals(2))
    }

    @Test
    fun `a half typed amount is not flagged as too precise`() {
        // "0." parses to nothing yet; there is no precision to judge.
        assertFalse(NumberTextFieldInnerState().exceedsAssetDecimals(2))
    }

    // endregion

    // region blank input — what hands authority back to the pay side

    @Test
    fun `an empty field is blank input`() {
        assertTrue(NumberTextFieldInnerState().isBlankInput())
    }

    @Test
    fun `a mid-typing value is not blank input even though it has no amount yet`() {
        val midTyping =
            NumberTextFieldInnerState(
                innerTextFieldState =
                    NumberTextFieldInnerState().innerTextFieldState.copy(
                        value = stringRes("0.")
                    ),
                amount = null,
                lastValidAmount = null
            )
        assertFalse(midTyping.isBlankInput())
    }

    // endregion

    private fun amountState(value: String) = NumberTextFieldInnerState.fromAmount(BigDecimal(value))
}
