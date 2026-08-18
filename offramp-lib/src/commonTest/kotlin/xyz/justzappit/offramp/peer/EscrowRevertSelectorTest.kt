// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.peer

import xyz.justzappit.evm.abi.Selector4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class EscrowRevertSelectorTest {
    /**
     * Read off EscrowV2 at 0x777777779d229cdF3110e9de47943791c26300Ef on Base mainnet. A signature
     * typo would still derive a consistent selector, so the deployed values are what pins it.
     */
    private val deployed =
        mapOf(
            EscrowRevert.AMOUNT_EXCEEDS_AVAILABLE to "0x68d92603",
            EscrowRevert.DEPOSIT_NOT_FOUND to "0xac2ded58",
            EscrowRevert.UNAUTHORIZED_CALLER_OR_DELEGATE to "0x9a313e59",
            EscrowRevert.UNAUTHORIZED_CALLER to "0x536dd9ef",
            EscrowRevert.DEPOSIT_ALREADY_IN_STATE to "0x58efc881",
            EscrowRevert.PAYMENT_METHOD_NOT_WHITELISTED to "0x060f97e5",
            EscrowRevert.CURRENCY_NOT_SUPPORTED to "0xd76cc8fa",
            EscrowRevert.INVALID_ORACLE_ADAPTER to "0xde5c514f",
            EscrowRevert.ARRAY_LENGTH_MISMATCH to "0xfa5dbe08",
            EscrowRevert.EMPTY_PAYEE_DETAILS to "0xbfa34c3a",
            EscrowRevert.ZERO_CONVERSION_RATE to "0x247af9ce",
            EscrowRevert.CURRENCY_ALREADY_EXISTS to "0xce831259",
            EscrowRevert.PAYMENT_METHOD_ALREADY_EXISTS to "0xde5a6b73",
            EscrowRevert.INVALID_SPREAD to "0xf1834869",
            EscrowRevert.ADAPTER_CONFIG_TOO_LONG to "0x539b6fb0",
            EscrowRevert.INVALID_RANGE to "0x2457cde7",
            EscrowRevert.ZERO_MIN_VALUE to "0x534c8fac",
            EscrowRevert.ZERO_VALUE to "0x7c946ed7",
            EscrowRevert.ZERO_ADDRESS to "0xd92e233d",
        )

    @Test
    fun `every derived selector matches the deployed contract`() {
        assertEquals(EscrowRevert.entries.size, deployed.size, "every revert must be pinned")
        deployed.forEach { (revert, hex) ->
            assertEquals(hex, revert.selector.hex, revert.name)
        }
    }

    @Test
    fun `selectors are unique so a lookup cannot be ambiguous`() {
        val distinct = EscrowRevert.entries.map { it.selector.hex }.toSet()
        assertEquals(EscrowRevert.entries.size, distinct.size)
    }

    @Test
    fun `fromSelector round-trips every entry and rejects an unknown one`() {
        EscrowRevert.entries.forEach { revert ->
            val probe = Selector4.fromHex(revert.selector.hex)
            assertEquals(revert, EscrowRevert.fromSelector(probe), revert.name)
        }
        assertEquals(null, EscrowRevert.fromSelector(Selector4.fromHex("0xdeadbeef")))
        assertEquals(null, EscrowRevert.fromSelector(null))
    }

    /** A wrong selector here swallows a real failure as "already in that state". */
    @Test
    fun `the benign revert is the only benign one`() {
        val benign = EscrowRevert.entries.filter { it.isBenign }
        assertEquals(listOf(EscrowRevert.DEPOSIT_ALREADY_IN_STATE), benign)
        assertNotNull(EscrowRevert.fromSelector(Selector4.fromHex("0x58efc881"))?.takeIf { it.isBenign })
    }
}
