// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.orchestrator

import xyz.justzappit.evm.abi.Selector4
import xyz.justzappit.evm.abi.SolidityErrors
import xyz.justzappit.evm.util.hexToBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KnownRevertsTest {
    // -- Curated KnownRevertReason mappings --------------------------------------------------

    @Test
    fun `0x91da284f maps to BuyOrderAmountExceedsLimit`() {
        // Regression: prior to the wholesale-port refactor, this selector was labelled
        // InsufficientReputation. The SDK's canonical name is BuyOrderAmountExceedsLimit; the
        // _functional_ "you need more RP" effect comes from txLimit = RP × multiplier, so the
        // user copy is similar — but the enum + sdkErrorName must match the SDK.
        assertEquals(KnownRevertReason.BuyOrderAmountExceedsLimit, KnownReverts.explain(selectorOf("0x91da284f")))
        assertEquals("BUY_ORDER_AMOUNT_EXCEEDS_LIMIT", KnownReverts.sdkName(selectorOf("0x91da284f")))
    }

    @Test
    fun `0x412dd2b1 maps to the real InsufficientReputation case`() {
        assertEquals(KnownRevertReason.InsufficientReputation, KnownReverts.explain(selectorOf("0x412dd2b1")))
        assertEquals("INSUFFICIENT_RP", KnownReverts.sdkName(selectorOf("0x412dd2b1")))
    }

    @Test
    fun `0x5d04ff4c maps to NotEnoughEligibleMerchants`() {
        assertEquals(KnownRevertReason.NotEnoughEligibleMerchants, KnownReverts.explain(selectorOf("0x5d04ff4c")))
    }

    @Test
    fun `all three USDC-transfer-failed selectors collapse to one curated reason`() {
        val selectors = listOf("0x149f9fca", "0x47bfece5", "0x279bbc0c")
        for (s in selectors) {
            assertEquals(
                KnownRevertReason.UsdcTransferFailed,
                KnownReverts.explain(selectorOf(s)),
                "selector $s should map to UsdcTransferFailed",
            )
        }
    }

    @Test
    fun `setSellOrderUpi-phase selectors are curated`() {
        assertEquals(KnownRevertReason.UpiAlreadySent, KnownReverts.explain(selectorOf("0xc1654697")))
        assertEquals(KnownRevertReason.InvalidOrderUpi, KnownReverts.explain(selectorOf("0xaa60ec26")))
        assertEquals(KnownRevertReason.OrderNotAccepted, KnownReverts.explain(selectorOf("0x6b1b90b4")))
        assertEquals(KnownRevertReason.OrderExpired, KnownReverts.explain(selectorOf("0xc56873ba")))
    }

    @Test
    fun `placeOrder-phase guardrail selectors are curated`() {
        assertEquals(KnownRevertReason.OrderAmountExceedsLimit, KnownReverts.explain(selectorOf("0xf42e41a1")))
        assertEquals(KnownRevertReason.SellAmountExceedsFiatLimit, KnownReverts.explain(selectorOf("0xbba2edf9")))
        assertEquals(KnownRevertReason.CurrencyNotSupported, KnownReverts.explain(selectorOf("0x02a6fdd2")))
        assertEquals(KnownRevertReason.UserIsBlacklisted, KnownReverts.explain(selectorOf("0xebb6f34b")))
        assertEquals(KnownRevertReason.ExchangeNotOperational, KnownReverts.explain(selectorOf("0x4bbac5de")))
    }

    @Test
    fun `null selector resolves to null reason`() {
        assertNull(KnownReverts.explain(null))
    }

    // -- Wholesale KnownContractErrors long-tail (uncurated but still labelled) --------------

    @Test
    fun `uncurated selector still resolves to an SDK name and message via the wholesale table`() {
        // OrderAlreadyCompleted is one of the ~175 selectors we don't curate — it should not produce
        // a KnownRevertReason but must produce a non-null sdkName + sdkMessage so the UI can show
        // "Contract error: Order already marked completed" instead of a raw 4-byte selector.
        val s = selectorOf("0x03683687")
        assertNull(KnownReverts.explain(s))
        assertEquals("ORDER_ALREADY_COMPLETED", KnownReverts.sdkName(s))
        assertEquals("Order already marked completed", KnownReverts.sdkMessage(s))
    }

    @Test
    fun `ORDER_NOT_ACCEPTED and ORDER_NOT_PLACED are distinct`() {
        // Regression for the hand-merged table that mapped BOTH 0x6b1b90b4 and 0x58db8ed6 to
        // "ORDER_NOT_PLACED_TO_BE_ACCEPTED" (and 0x7f61b868 / 0xc1654697 to the same string). The
        // SDK's errors.ts is now the single source of truth, so every code is distinct.
        assertEquals("ORDER_NOT_ACCEPTED", KnownContractErrors.nameFor(Selector4.fromHex("0x6b1b90b4")))
        assertEquals("ORDER_NOT_PLACED", KnownContractErrors.nameFor(Selector4.fromHex("0x58db8ed6")))
        assertEquals("ORDER_ALREADY_PAID", KnownContractErrors.nameFor(Selector4.fromHex("0x7f61b868")))
        assertEquals("UPI_ALREADY_SENT", KnownContractErrors.nameFor(Selector4.fromHex("0xc1654697")))
    }

    @Test
    fun `KnownContractErrors covers every curated selector`() {
        // Every curated selector must also exist in the wholesale SDK table. If this fails, the
        // curated map drifted from the SDK and a re-run of generate-revert-selectors.ts is overdue.
        // Iterating KnownReverts.curatedSelectors (rather than a hand-mirrored literal list) means
        // adding a new CURATED entry automatically extends test coverage.
        for (s in KnownReverts.curatedSelectors) {
            assertNotNull(
                KnownContractErrors.nameFor(s),
                "Curated selector $s missing from KnownContractErrors — regenerate the wholesale table",
            )
        }
    }

    @Test
    fun `sdkMessage renders human-readable SDK copy for the long tail`() {
        assertEquals("Order expired", KnownReverts.sdkMessage(selectorOf("0xc56873ba")))
        assertEquals("Order not placed to be accepted", KnownReverts.sdkMessage(selectorOf("0x6b1b90b4")))
        assertEquals("USDC transfer failed", KnownReverts.sdkMessage(selectorOf("0x149f9fca")))
        assertNull(KnownReverts.sdkMessage(selectorOf("0xdeadbeef")))
    }

    @Test
    fun `wholesale table is non trivially populated`() {
        // Guards against a future bad regen producing an empty or shrunken table. The Entry data
        // class enforces every selector has both a name and a message, so the previous "lockstep"
        // parity check is now structural.
        assertTrue(KnownContractErrors.size >= 120, "expected ≥120 mapped selectors, got ${KnownContractErrors.size}")
    }

    @Test
    fun `KnownContractErrors returns null for genuinely unknown selectors`() {
        assertNull(KnownContractErrors.nameFor(Selector4.fromHex("0xdeadbeef")))
        assertNull(KnownContractErrors.nameFor(null))
        assertNull(KnownReverts.sdkName(selectorOf("0xdeadbeef")))
    }

    @Test
    fun `explain returns null for selectors outside the curated set`() {
        // 0x03683687 = ORDER_ALREADY_COMPLETED — known by SDK but not actionable enough
        // to be in KnownRevertReason. explain() must say null; sdkName() must still resolve.
        assertNull(KnownReverts.explain(selectorOf("0x03683687")))
    }

    // -- ERC-4337 bundler error decoding -----------------------------------------------------

    @Test
    fun `0xea8e4eb5 maps to NotAuthorized`() {
        assertEquals(KnownRevertReason.NotAuthorized, KnownReverts.explain(selectorOf("0xea8e4eb5")))
        assertEquals("NOT_AUTHORIZED", KnownReverts.sdkName(selectorOf("0xea8e4eb5")))
    }

    @Test
    fun `selectorFromMessage recovers a selector from a bundler revert message`() {
        val msg = "UserOperation reverted during simulation with reason: 0xea8e4eb5"
        val selector = KnownReverts.selectorFromMessage(msg)
        assertEquals(Selector4.fromHex("0xea8e4eb5"), selector)
        assertEquals(KnownRevertReason.NotAuthorized, KnownReverts.explain(selector))
    }

    @Test
    fun `selectorFromMessage ignores non-selector input`() {
        assertNull(KnownReverts.selectorFromMessage(null))
        assertNull(KnownReverts.selectorFromMessage("AA25 invalid account nonce"))
        // A full 20-byte address must not be mistaken for a 4-byte selector.
        assertNull(KnownReverts.selectorFromMessage("sender 0xdD53a3Db48e5b69F34Abc1fA3156Dc3d0c269D5E rejected"))
    }

    // -- SolidityErrors regression preserved -------------------------------------------------

    @Test
    fun `SolidityErrors decodes an Error string payload`() {
        // 0x08c379a0 || offset(0x20) || length(0x05) || "hello" || padding
        val payload =
            (
                "0x08c379a0" +
                    "0000000000000000000000000000000000000000000000000000000000000020" +
                    "0000000000000000000000000000000000000000000000000000000000000005" +
                    "68656c6c6f000000000000000000000000000000000000000000000000000000"
            ).hexToBytes()
        assertEquals("hello", SolidityErrors.decodeErrorString(payload))
    }

    @Test
    fun `SolidityErrors returns null for non-Error payloads`() {
        assertNull(SolidityErrors.decodeErrorString("0x91da284f".hexToBytes()))
        assertNull(SolidityErrors.decodeErrorString(byteArrayOf()))
    }

    private fun selectorOf(hex: String): Selector4 = Selector4.fromHex(hex)
}
