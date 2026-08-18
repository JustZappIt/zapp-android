// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.p2p

import xyz.justzappit.evm.util.hexToBytes
import xyz.justzappit.evm.util.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class OrderFeeDetailsDecoderTest {
    @Test
    fun `decodes a populated post completion tuple`() {
        // 7 packed uint words for: fixedFeePaid=50_000 (0.05 USDC), tipsPaid=0,
        // acceptedTimestamp=1_779_500_000, paidTimestamp=1_779_999_000, reserved2=0,
        // actualUsdcAmount=5_062_500 (5.0625 USDC), actualFiatAmount=445_000_000 (445 fiat-µ).
        val hex =
            (
                "000000000000000000000000000000000000000000000000000000000000c350" + // fixedFeePaid 50_000
                    "0000000000000000000000000000000000000000000000000000000000000000" + // tipsPaid 0
                    "000000000000000000000000000000000000000000000000000000006a1103e0" + // acceptedTs 1_779_500_000
                    "000000000000000000000000000000000000000000000000000000006a18a118" + // paidTs 1_779_999_000
                    "0000000000000000000000000000000000000000000000000000000000000000" + // reserved2 0
                    "00000000000000000000000000000000000000000000000000000000004d3f64" + // actualUsdc 5_062_500
                    "000000000000000000000000000000000000000000000000000000001a862940" // actualFiat 445_000_000
            ).hexToBytes()

        val decoded = OrderFeeDetailsDecoder.decode(hex)
        assertEquals(Usdc6.ofMicros(50_000), decoded.fixedFeePaid)
        assertEquals(1_779_500_000L, decoded.acceptedAtEpochSeconds)
        assertEquals(1_779_999_000L, decoded.paidAtEpochSeconds)
        assertEquals(Usdc6.ofMicros(5_062_500), decoded.actualUsdcAmount)
        assertEquals(Usdc6.ofMicros(445_000_000), decoded.actualFiatAmount)
    }

    @Test
    fun `timestamps treated as null when contract fields are zero`() {
        // pre-acceptance shape: timestamps are 0 because the contract hasn't written them yet.
        val hex = ("00".repeat(32 * 7)).hexToBytes()
        val decoded = OrderFeeDetailsDecoder.decode(hex)
        assertEquals(Usdc6.ZERO, decoded.fixedFeePaid)
        assertNull(decoded.acceptedAtEpochSeconds)
        assertNull(decoded.paidAtEpochSeconds)
        assertEquals(Usdc6.ZERO, decoded.actualUsdcAmount)
        assertEquals(Usdc6.ZERO, decoded.actualFiatAmount)
    }

    @Test
    fun `truncated return data is rejected`() {
        val tooShort = ("00".repeat(32 * 6)).hexToBytes()
        assertFailsWith<IllegalArgumentException> { OrderFeeDetailsDecoder.decode(tooShort) }
    }

    @Test
    fun `getAdditionalOrderDetailsCalldata uses correct selector and arg encoding`() {
        // selector(getAdditionalOrderDetails(uint256)) padded to 4 bytes ||  orderId padded to 32.
        val calldata =
            DiamondCalls.getAdditionalOrderDetailsCalldata(
                xyz.justzappit.evm.math
                    .bigIntegerValueOf(42)
            )
        // Sanity: 4-byte selector + 32-byte arg = 36 bytes total.
        assertEquals(36, calldata.size)
        // Last 32 bytes should be uint256(42) = 0x...2a
        val tail = calldata.copyOfRange(4, 36)
        assertEquals(
            "000000000000000000000000000000000000000000000000000000000000002a",
            tail.toHex(),
        )
    }
}
