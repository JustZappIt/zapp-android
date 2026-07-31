package co.electriccoin.zcash.ui.screen.unifiedsend

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SendBalanceHeaderTest {
    @Test
    fun `keeps sub-unit balances at one visual size`() {
        assertFalse(shouldDeemphasizeFraction("${'$'}0.05423", '.'))
        assertFalse(shouldDeemphasizeFraction("0.05423 ZEC", '.'))
    }

    @Test
    fun `deemphasizes fractions when whole amount is non-zero`() {
        assertTrue(shouldDeemphasizeFraction("${'$'}12.34", '.'))
        assertTrue(shouldDeemphasizeFraction("€12,34", ','))
    }
}
