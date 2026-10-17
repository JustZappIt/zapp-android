package co.electriccoin.zcash.ui.design.component.zapp

import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigInteger

class AmountPresetTest {
    @Test
    fun `a share rounds down, so it never asks for more than there is`() {
        assertEquals(19L, AmountPreset.TWENTY.of(99L))
        assertEquals(49L, AmountPreset.HALF.of(99L))
    }

    @Test
    fun `ALL is the balance to the last unit`() {
        assertEquals(99L, AmountPreset.ALL.of(99L))
        val large = BigInteger("2100000000000000")
        assertEquals(large, AmountPreset.ALL.of(large))
    }

    @Test
    fun `a share of nothing is nothing`() {
        AmountPreset.entries.forEach { assertEquals(0L, it.of(0L)) }
    }
}
