// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.p2p

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** EMVCo TLV + CRC-16/CCITT-FALSE parity with `@p2pdotme/sdk` `qr-parsers/utils`. */
class EmvQrTest {
    // Real SDK PIX fixtures; the last 8 chars are the `6304` tag + the expected CRC.
    private val cpfFixture =
        "00020126330014BR.GOV.BCB.PIX0111000000000005204000053039865802BR" +
            "5910NOME TESTE6008CIDADE B62070503***6304D727"
    private val dynamicFixture =
        "00020101021226590014BR.GOV.BCB.PIX2537example.test/v2/cobv/aaaabbbbccccdddd5204000053039865802BR" +
            "5910LOJA TESTE6008CIDADE D62160512ORDEMTEST0016304B02B"

    @Test
    fun `calculateCrc16 matches known EMVCo outputs`() {
        assertEquals("D727", EmvQr.calculateCrc16(cpfFixture.dropLast(8)))
        assertEquals("B02B", EmvQr.calculateCrc16(dynamicFixture.dropLast(8)))
    }

    @Test
    fun `verifyCrc16 accepts valid fixtures and rejects tampering`() {
        assertTrue(EmvQr.verifyCrc16(cpfFixture))
        assertTrue(EmvQr.verifyCrc16(dynamicFixture))
        assertFalse(EmvQr.verifyCrc16(cpfFixture.dropLast(4) + "FFFF"))
        assertFalse(EmvQr.verifyCrc16("6304ABCD")) // CRC tag with no preceding data still recomputes mismatched
        assertFalse(EmvQr.verifyCrc16("short"))
    }

    @Test
    fun `parseTlv reads sequential tags and stops on truncation`() {
        val ok = EmvQr.parseTlv("0002015904ACME")
        assertEquals(listOf(EmvQr.TlvEntry("00", "01"), EmvQr.TlvEntry("59", "ACME")), ok)

        // tag 59 declares length 10 but only 4 value chars follow -> the entry is dropped.
        assertTrue(EmvQr.parseTlv("5910ABCD").isEmpty())
    }

    @Test
    fun `extractTags is last-wins and filters to requested tags`() {
        val tags = EmvQr.parseTlv("5903ONE5903TWO")
        assertEquals("TWO", EmvQr.extractTags("5903ONE5903TWO", setOf("59"))["59"])
        assertEquals(2, tags.size)
    }
}
