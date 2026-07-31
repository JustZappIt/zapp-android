package co.electriccoin.zcash.ui.screen.request.model

import androidx.test.filters.SmallTest
import cash.z.ecc.android.sdk.model.FiatCurrencyConversion
import co.electriccoin.zcash.ui.test.getAppContext
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.time.Clock

class AmountStateTest {
    private val conversion =
        FiatCurrencyConversion(
            timestamp = Clock.System.now(),
            priceOfZec = 100.0
        )

    // region toZecStringFloored

    /**
     * Review point 1: toZecStringFloored() uses stringRes(Zatoshi) which by default includes
     * the "ZEC" ticker (TickerLocation.AFTER). The result should be a plain number suitable
     * for use in a ZIP-321 URI — if this test fails, the ticker is being appended and QR codes
     * on the request screen will be malformed.
     */
    @Test
    @SmallTest
    fun toZecStringFloored_doesNotContainTicker() {
        val state = AmountState(amount = "10", currency = RequestCurrency.FIAT, isValid = true)

        val result = state.toZecStringFloored(conversion, getAppContext())

        assertFalse(
            result.contains("ZEC"),
            "toZecStringFloored should return a plain number, got: \"$result\""
        )
    }

    @Test
    @SmallTest
    fun toZecStringFloored_resultIsParseableNumber() {
        val state = AmountState(amount = "10", currency = RequestCurrency.FIAT, isValid = true)

        val result = state.toZecStringFloored(conversion, getAppContext())

        assertNotNull(
            result.replace(",", ".").toBigDecimalOrNull(),
            "toZecStringFloored result should be a valid number, got: \"$result\""
        )
    }

    // endregion

    // region toFiatString

    /**
     * Review point 2: toFiatString() with invalid ZEC input used to return "" (empty string)
     * via runCatching { }.getOrElse { "" }. The new implementation falls through to
     * stringResByNumber(BigDecimal(0), maxDecimals = 2) which returns "0.00".
     * If this test fails, the UI may show "0.00" where it previously showed nothing.
     */
    @Test
    @SmallTest
    fun toFiatString_invalidZecAmount_returnsEmpty() {
        val state = AmountState(amount = "not_a_number", currency = RequestCurrency.ZEC, isValid = false)

        val result = state.toFiatString(getAppContext(), conversion)

        assertEquals("", result, "Invalid input should return empty string, got: \"$result\"")
    }

    @Test
    @SmallTest
    fun toFiatString_emptyAmount_returnsEmpty() {
        val state = AmountState(amount = "", currency = RequestCurrency.ZEC, isValid = false)

        val result = state.toFiatString(getAppContext(), conversion)

        assertEquals("", result, "Empty input should return empty string, got: \"$result\"")
    }

    @Test
    @SmallTest
    fun toFiatString_validZecAmount_returnsNonEmptyString() {
        val state = AmountState(amount = "1", currency = RequestCurrency.ZEC, isValid = true)

        val result = state.toFiatString(getAppContext(), conversion)

        assertFalse(result.isEmpty(), "Valid ZEC amount should produce a non-empty fiat string")
    }

    // endregion
}
