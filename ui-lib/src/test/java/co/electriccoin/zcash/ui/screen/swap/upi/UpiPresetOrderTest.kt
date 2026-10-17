package co.electriccoin.zcash.ui.screen.swap.upi

import co.electriccoin.zcash.ui.design.component.zapp.AmountPreset
import org.junit.Test
import xyz.justzappit.offramp.p2p.Usdc6
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpiPresetOrderTest {
    @Test
    fun `ALL orders what the balance pays once the small-order fee is set aside`() {
        assertEquals(usdc("24.5"), presetOrder(AmountPreset.ALL, usdc("25.5"), CAP, ::smallOrderFee))
    }

    @Test
    fun `ALL above the small-order threshold pays no fee and orders the whole balance`() {
        assertEquals(usdc("60"), presetOrder(AmountPreset.ALL, usdc("60"), CAP, ::smallOrderFee))
    }

    @Test
    fun `ALL past one order's cap orders exactly the cap`() {
        assertEquals(CAP, presetOrder(AmountPreset.ALL, usdc("250"), CAP, ::smallOrderFee))
    }

    @Test
    fun `ALL just past the cap still leaves room for a fee the cap pays`() {
        // A fee that applies at the cap itself: 100.5 can't order 100 and pay 1 on top.
        val fee = { _: Usdc6 -> usdc("1") }

        assertEquals(usdc("99.5"), presetOrder(AmountPreset.ALL, usdc("100.5"), CAP, fee))
    }

    @Test
    fun `a share pays its fee on top and is capped like any order`() {
        assertEquals(usdc("12.75"), presetOrder(AmountPreset.HALF, usdc("25.5"), CAP, ::smallOrderFee))
        assertEquals(CAP, presetOrder(AmountPreset.HALF, usdc("300"), CAP, ::smallOrderFee))
    }

    @Test
    fun `ALL just above the fee threshold is priced on what the order places, so it still pays from the balance`() {
        // Fiat floors to 2 dp at ₹97 and back to USDC at 6 dp: 50.000001 places exactly 50, which pays the fee.
        val placed = { order: Usdc6 ->
            val inr = order.whole.multiply(RATE).setScale(2, RoundingMode.FLOOR)
            Usdc6.ofWhole(inr.divide(RATE, 6, RoundingMode.FLOOR))
        }
        val balance = usdc("50.000001")

        val order = checkNotNull(presetOrder(AmountPreset.ALL, balance, CAP) { smallOrderFee(placed(it)) })

        val placedOrder = placed(order)
        assertTrue(placedOrder + smallOrderFee(placedOrder) <= balance)
    }

    @Test
    fun `a balance the fee takes all of orders nothing`() {
        assertNull(presetOrder(AmountPreset.ALL, usdc("1"), CAP, ::smallOrderFee))
    }

    private companion object {
        val CAP = usdc("100")
        val RATE = BigDecimal("97")

        fun usdc(whole: String) = Usdc6.ofWhole(BigDecimal(whole))

        /** A 1 USDC fee on orders up to 50 USDC, as the Diamond charges small orders. */
        fun smallOrderFee(order: Usdc6): Usdc6 = if (order <= usdc("50")) usdc("1") else Usdc6.ZERO
    }
}
