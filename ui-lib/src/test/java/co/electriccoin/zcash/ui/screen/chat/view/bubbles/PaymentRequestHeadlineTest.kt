package co.electriccoin.zcash.ui.screen.chat.view.bubbles

import cash.z.ecc.android.sdk.model.FiatCurrency
import co.electriccoin.zcash.ui.common.wallet.ZecFiatRate
import io.mockk.every
import io.mockk.mockk
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PaymentRequestHeadlineTest {
    // android.icu is a stub on the JVM, so the live rate's symbol is stubbed.
    private val rate =
        ZecFiatRate(
            pricePerZec = BigDecimal("50"),
            currency = mockk<FiatCurrency> { every { symbol } returns "$" },
        )

    @Test
    fun `a request typed in ZEC leads with ZEC and puts the live price under it`() {
        val parsed = parsePaymentRequest("""{"amount":0.25,"token":"ZEC"}""", rate)

        assertEquals("0.25 ZEC", parsed.amountLabel)
        assertEquals("≈ $12.50", parsed.equivalentLabel)
    }

    @Test
    fun `a request typed in fiat leads with the price it was typed at`() {
        val parsed =
            parsePaymentRequest("""{"amount":0.25,"token":"ZEC","fiatAmount":13.00,"fiatCurrency":"USD"}""", rate)

        // The embedded label resolves its own symbol, which falls back to the code on the JVM.
        assertTrue(parsed.amountLabel.endsWith("13.00"), parsed.amountLabel)
        assertEquals("≈ 0.25 ZEC", parsed.equivalentLabel)
    }

    @Test
    fun `without a live rate only ZEC is shown`() {
        val parsed =
            parsePaymentRequest("""{"amount":0.25,"token":"ZEC","fiatAmount":13.00,"fiatCurrency":"USD"}""", null)

        assertEquals("0.25 ZEC", parsed.amountLabel)
        assertNull(parsed.equivalentLabel)
    }
}
