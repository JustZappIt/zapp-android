package co.electriccoin.zcash.ui.screen.settings.p2p

import org.junit.Test
import xyz.justzappit.offramp.p2p.CurrencyCode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `available` gates whether a corridor can be selected at all, so both directions matter: leaving a
 * staffed corridor off hides a working rail, and turning an unstaffed one on lets a user place an
 * order no merchant can accept.
 */
class P2pPaymentMethodTest {
    /**
     * Bolivia and Peru are implemented but their circles have no assignable merchant, so they show
     * as "coming soon" rather than taking orders that can only expire. This test exists to make
     * enabling them a deliberate act — if you flip one, re-run the circle check first.
     */
    @Test
    fun `corridors without merchants are not selectable`() {
        val gated = setOf(CurrencyCode.Bob, CurrencyCode.Pen)

        P2pPaymentMethod.entries.forEach { method ->
            if (method.currency in gated) {
                assertFalse(method.available, "${method.currency} has no assignable merchant")
            } else {
                assertTrue(method.available, "${method.currency} is staffed and should be offered")
            }
        }
    }

    /**
     * `fromCurrency` falls back to UPI, so a corridor missing an entry here does not fail loudly —
     * it silently renders as India.
     */
    @Test
    fun `every currency has its own entry rather than falling back to UPI`() {
        assertEquals(CurrencyCode.entries.size, P2pPaymentMethod.entries.size)

        CurrencyCode.entries.forEach { currency ->
            assertEquals(
                currency,
                P2pPaymentMethod.fromCurrency(currency).currency,
                "$currency must map to its own payment method, not UPI",
            )
        }
    }
}
