// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.p2p

import xyz.justzappit.evm.math.BigDecimal
import xyz.justzappit.evm.util.hexToBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PriceConfigDecoderTest {
    @Test
    fun `decodes sellPrice from the live Sepolia getPriceConfig INR response`() {
        // Captured 2026-05-21 from eth_call to 0xce868398...532aE2 on sepolia.base.org.
        // Wire format is four packed uint256s with sellPrice at slot 1.
        val raw =
            (
                "00000000000000000000000000000000000000000000000000000000056c8cc0" +
                    "00000000000000000000000000000000000000000000000000000000054e0840" +
                    "0000000000000000000000000000000000000000000000000000000000000000" +
                    "000000000000000000000000000000000000000000000000000000000016e360"
            ).hexToBytes()

        val cfg = PriceConfigDecoder.decode(raw)
        assertEquals(Usdc6.ofMicros(89_000_000L), cfg.sellPrice)
    }

    @Test
    fun `sellPriceAsRate scales by 6 decimals`() {
        val cfg = PriceConfig(sellPrice = Usdc6.ofMicros(89_178_176L))
        // compareTo because BigDecimal.equals is scale-sensitive (89.178176 != 89.17817600).
        assertEquals(0, BigDecimal("89.178176").compareTo(cfg.sellPriceAsRate()))
    }

    @Test
    fun `fiatForUsdc multiplies by the sell rate`() {
        val cfg = PriceConfig(sellPrice = Usdc6.ofMicros(89_178_176L))
        // 5 × 89.178176 = 445.89088 → 445.89 (HALF_UP at 2dp).
        assertEquals(BigDecimal("445.89"), cfg.fiatForUsdc(BigDecimal("5")))
    }

    @Test
    fun `usdcForFiat divides by the sell rate`() {
        val cfg = PriceConfig(sellPrice = Usdc6.ofMicros(89_178_176L))
        // 100 / 89.178176 ≈ 1.12135057… → 1.1214 (HALF_UP at 4dp).
        assertEquals(BigDecimal("1.1214"), cfg.usdcForFiat(BigDecimal("100")))
    }

    @Test
    fun `decode rejects truncated input`() {
        assertFailsWith<IllegalArgumentException> {
            PriceConfigDecoder.decode(ByteArray(127))
        }
    }
}
