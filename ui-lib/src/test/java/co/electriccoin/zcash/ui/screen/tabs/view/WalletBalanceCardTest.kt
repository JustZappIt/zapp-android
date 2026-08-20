package co.electriccoin.zcash.ui.screen.tabs.view

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WalletBalanceCardTest {
    @Test
    fun `scramble preserves currency formatting while replacing digits`() {
        val clearText = "₦1,234.56"

        val scrambled = scrambledBalanceFrame(clearText, frame = 0, revealing = false)
        val encrypted = scrambledBalanceFrame(clearText, frame = Int.MAX_VALUE, revealing = false)

        assertEquals('₦', scrambled.first())
        assertEquals(',', scrambled[2])
        assertEquals('.', scrambled[6])
        assertFalse(scrambled.first { it != '₦' }.isDigit())
        assertFalse(encrypted.any(Char::isDigit))
    }

    @Test
    fun `decrypting frame progressively restores clear balance`() {
        val clearText = "\$12.34 ZEC"

        val firstFrame = scrambledBalanceFrame(clearText, frame = 0, revealing = true)
        val lastFrame = scrambledBalanceFrame(clearText, frame = Int.MAX_VALUE, revealing = true)

        assertTrue(firstFrame.any { it in "#%&?*+=§" })
        assertEquals(clearText, lastFrame)
    }
}
