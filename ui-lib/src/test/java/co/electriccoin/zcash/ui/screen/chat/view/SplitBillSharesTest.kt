package co.electriccoin.zcash.ui.screen.chat.view

import cash.z.ecc.android.sdk.model.FiatCurrency
import co.electriccoin.zcash.ui.common.wallet.ZecFiatRate
import co.electriccoin.zcash.ui.screen.chat.room.SplitParticipant
import io.mockk.mockk
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class SplitBillSharesTest {
    private val currency = mockk<FiatCurrency>()
    private val rate = ZecFiatRate(pricePerZec = BigDecimal("50"), currency = currency)
    private val participants = listOf(SplitParticipant("pk", "Alice"))

    @Test
    fun `a share typed in fiat carries the typed price`() {
        val share = computeShares(participants, emptyMap(), BigDecimal("13"), isFiat = true, fiatRate = rate).single()

        assertEquals(0, BigDecimal("0.26").compareTo(share.amount))
        assertEquals(BigDecimal("13.00"), share.fiat?.amount)
        assertSame(currency, share.fiat?.currency)
    }

    @Test
    fun `a share typed in ZEC carries no fiat`() {
        val share =
            computeShares(participants, emptyMap(), BigDecimal("0.25"), isFiat = false, fiatRate = rate).single()

        assertEquals(BigDecimal("0.25"), share.amount)
        assertNull(share.fiat)
    }
}
