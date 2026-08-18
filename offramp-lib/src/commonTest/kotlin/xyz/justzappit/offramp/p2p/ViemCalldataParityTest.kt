// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.p2p

import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.math.bigIntegerOne
import xyz.justzappit.evm.math.bigIntegerValueOf
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.util.toHex
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Byte-for-byte parity check against viem's `encodeFunctionData`. Fixtures captured
 * via `docs/integrations/scripts/generate-calldata-fixtures.ts`. If the Kotlin
 * encoder ever drifts from viem, exactly one of these assertions will fail.
 */
class ViemCalldataParityTest {
    @Test
    fun `approve calldata matches viem`() {
        val got =
            Erc20Calls
                .approveCalldata(
                    spender = Address.parse("0xce868398FDaDcA368EAc203222874D6888532aE2"),
                    amount = Usdc6.ofMicros(1_000_000),
                ).toHex()
        assertEquals(VIEM_APPROVE.removePrefix("0x"), got)
    }

    @Test
    fun `placeOrder PAY INR calldata with relay pubkey matches viem`() {
        val got =
            DiamondCalls
                .placeOrderCalldata(
                    PlaceOrderArgs(
                        relayPubKeyEthCrypto = RELAY_PUBKEY,
                        usdcAmount = Usdc6.ofMicros(5_000_000),
                        recipientAddress = Address.parse("0x000000000000000000000000000000000000dead"),
                        orderType = OrderType.PAY,
                        currency = CurrencyCode.Inr,
                        circleId = bigIntegerOne,
                    ),
                ).toHex()
        assertEquals(VIEM_PLACE_ORDER.removePrefix("0x"), got)
    }

    @Test
    fun `setSellOrderUpi calldata matches viem`() {
        val got =
            DiamondCalls
                .setSellOrderUpiCalldata(
                    orderId = bigIntegerValueOf(42),
                    encryptedUpiHex = "a".repeat(170),
                ).toHex()
        assertEquals(VIEM_SET_SELL_ORDER_UPI.removePrefix("0x"), got)
    }

    @Test
    fun `getOrdersById calldata matches viem`() {
        val got = DiamondCalls.getOrdersByIdCalldata(bigIntegerValueOf(42)).toHex()
        assertEquals(VIEM_GET_ORDERS_BY_ID.removePrefix("0x"), got)
    }

    @Test
    fun `getPriceConfig calldata matches viem`() {
        val got = DiamondCalls.getPriceConfigCalldata(CurrencyCode.Inr).toHex()
        assertEquals(VIEM_GET_PRICE_CONFIG.removePrefix("0x"), got)
    }

    @Test
    fun `getSmallOrderThreshold calldata matches viem`() {
        val got = DiamondCalls.getSmallOrderThresholdCalldata(CurrencyCode.Inr).toHex()
        assertEquals(VIEM_GET_SMALL_ORDER_THRESHOLD.removePrefix("0x"), got)
    }

    @Test
    fun `getSmallOrderFixedFeePay calldata matches viem`() {
        val got = DiamondCalls.getSmallOrderFixedFeePayCalldata(CurrencyCode.Inr).toHex()
        assertEquals(VIEM_GET_SMALL_ORDER_FIXED_FEE_PAY.removePrefix("0x"), got)
    }

    @Test
    fun `getAssignableMerchantsFromCircle calldata matches viem`() {
        val got =
            DiamondCalls
                .getAssignableMerchantsFromCircleCalldata(
                    circleId = bigIntegerOne,
                    assignUpTo = bigIntegerValueOf(3),
                    currency = CurrencyCode.Inr,
                    user = Address.parse("0x000000000000000000000000000000000000beef"),
                    usdtAmount = Usdc6.ofMicros(5_000_000),
                    fiatAmount = Usdc6.ofMicros(418_000_000),
                    orderType = OrderType.PAY,
                ).toHex()
        assertEquals(VIEM_GET_ASSIGNABLE.removePrefix("0x"), got)
    }

    companion object {
        // Captured 2026-05-21 via `docs/integrations/scripts/generate-calldata-fixtures.ts`
        // running against viem 2.50.4 in /tmp.
        private const val RELAY_PUBKEY =
            "1b84c5567b126440995d3ed5aaba0565d71e1834604819ff9c17f5e9d5dd078f" +
                "70beaf8f588b541507fed6a642c5ab42dfdf8120a7f639de5122d47a69a8e8d1"

        private const val VIEM_APPROVE =
            "0x095ea7b3000000000000000000000000ce868398fdadca368eac203222874d68" +
                "88532ae200000000000000000000000000000000000000000000000000000000000f4240"

        private const val VIEM_PLACE_ORDER =
            "0x1dc46885" +
                "0000000000000000000000000000000000000000000000000000000000000140" +
                "00000000000000000000000000000000000000000000000000000000004c4b40" +
                "000000000000000000000000000000000000000000000000000000000000dead" +
                "0000000000000000000000000000000000000000000000000000000000000002" +
                "00000000000000000000000000000000000000000000000000000000000001e0" +
                "0000000000000000000000000000000000000000000000000000000000000200" +
                "494e520000000000000000000000000000000000000000000000000000000000" +
                "0000000000000000000000000000000000000000000000000000000000000000" +
                "0000000000000000000000000000000000000000000000000000000000000001" +
                "0000000000000000000000000000000000000000000000000000000000000000" +
                "0000000000000000000000000000000000000000000000000000000000000080" +
                "3162383463353536376231323634343039393564336564356161626130353635" +
                "6437316531383334363034383139666639633137663565396435646430373866" +
                "3730626561663866353838623534313530376665643661363432633561623432" +
                "6466646638313230613766363339646535313232643437613639613865386431" +
                "0000000000000000000000000000000000000000000000000000000000000000" +
                "0000000000000000000000000000000000000000000000000000000000000000"

        private const val VIEM_SET_SELL_ORDER_UPI =
            "0xe8576b23" +
                "000000000000000000000000000000000000000000000000000000000000002a" +
                "0000000000000000000000000000000000000000000000000000000000000060" +
                "0000000000000000000000000000000000000000000000000000000000000000" +
                "00000000000000000000000000000000000000000000000000000000000000aa" +
                "6161616161616161616161616161616161616161616161616161616161616161" +
                "6161616161616161616161616161616161616161616161616161616161616161" +
                "6161616161616161616161616161616161616161616161616161616161616161" +
                "6161616161616161616161616161616161616161616161616161616161616161" +
                "6161616161616161616161616161616161616161616161616161616161616161" +
                "6161616161616161616100000000000000000000000000000000000000000000"

        private const val VIEM_GET_ORDERS_BY_ID =
            "0xcea99cd6000000000000000000000000000000000000000000000000000000000000002a"

        private const val VIEM_GET_PRICE_CONFIG =
            "0x67c84efd494e520000000000000000000000000000000000000000000000000000000000"

        private const val VIEM_GET_SMALL_ORDER_THRESHOLD =
            "0x6b2d3913494e520000000000000000000000000000000000000000000000000000000000"

        private const val VIEM_GET_SMALL_ORDER_FIXED_FEE_PAY =
            "0x1e277523494e520000000000000000000000000000000000000000000000000000000000"

        private const val VIEM_GET_ASSIGNABLE =
            "0x36b0ec9a" +
                "0000000000000000000000000000000000000000000000000000000000000001" +
                "0000000000000000000000000000000000000000000000000000000000000003" +
                "494e520000000000000000000000000000000000000000000000000000000000" +
                "000000000000000000000000000000000000000000000000000000000000beef" +
                "00000000000000000000000000000000000000000000000000000000004c4b40" +
                "0000000000000000000000000000000000000000000000000000000018ea2c80" +
                "0000000000000000000000000000000000000000000000000000000000000002" +
                "0000000000000000000000000000000000000000000000000000000000000000"
    }
}
