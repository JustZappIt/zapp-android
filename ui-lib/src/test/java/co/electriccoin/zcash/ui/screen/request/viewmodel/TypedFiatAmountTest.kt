package co.electriccoin.zcash.ui.screen.request.viewmodel

import cash.z.ecc.android.sdk.model.FiatCurrency
import co.electriccoin.zcash.ui.common.wallet.ZecFiatRate
import co.electriccoin.zcash.ui.screen.request.model.RequestCurrency
import io.mockk.mockk
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class TypedFiatAmountTest {
    private val currency = mockk<FiatCurrency>()
    private val rate = ZecFiatRate(pricePerZec = BigDecimal("50"), currency = currency)

    @Test
    fun `a request typed in ZEC carries no fiat`() {
        assertNull(typedFiatAmount(RequestCurrency.ZEC, BigDecimal("0.25"), rate))
    }

    @Test
    fun `a request typed in fiat carries the typed price`() {
        val fiat = typedFiatAmount(RequestCurrency.FIAT, BigDecimal("13"), rate)

        assertEquals(BigDecimal("13.00"), fiat?.amount)
        assertSame(currency, fiat?.currency)
    }
}
