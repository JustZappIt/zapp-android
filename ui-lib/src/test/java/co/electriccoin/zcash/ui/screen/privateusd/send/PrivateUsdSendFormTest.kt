// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd.send

import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdAsset
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdSendMode
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdTokens
import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
import xyz.justzappit.railgun.RailgunNetwork
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PrivateUsdSendFormTest {
    @Test
    fun `a private send needs a railgun address`() {
        assertNotNull(form(PrivateUsdSendMode.PRIVATE, " $ZERO_K ").request(asset))
        assertNull(form(PrivateUsdSendMode.PRIVATE, LOWERCASE_0X).request(asset))
        assertNotNull(form(PrivateUsdSendMode.PRIVATE, "0zk1abc").recipientError())
    }

    @Test
    fun `a withdrawal takes a single-case address or a correctly checksummed one`() {
        assertNotNull(form(PrivateUsdSendMode.WITHDRAW, LOWERCASE_0X).request(asset))
        assertNotNull(form(PrivateUsdSendMode.WITHDRAW, CHECKSUMMED_0X).request(asset))
        assertNull(form(PrivateUsdSendMode.WITHDRAW, MISTYPED_0X).request(asset))
        assertNotNull(form(PrivateUsdSendMode.WITHDRAW, MISTYPED_0X).recipientError())
        assertNull(form(PrivateUsdSendMode.WITHDRAW, ZERO_K).request(asset))
    }

    @Test
    fun `nothing more than is available can be sent`() {
        val tooMuch = form(PrivateUsdSendMode.PRIVATE, ZERO_K, BigDecimal("12.35"))

        assertNotNull(tooMuch.amountError(asset))
        assertNull(tooMuch.request(asset))
        assertNull(form(PrivateUsdSendMode.PRIVATE, ZERO_K, BigDecimal("12.34")).amountError(asset))
    }

    @Test
    fun `the request carries the amount in base units and a trimmed recipient`() {
        val form = form(PrivateUsdSendMode.WITHDRAW, " $LOWERCASE_0X\n", BigDecimal("1.5"))
        val request = checkNotNull(form.request(asset))

        assertEquals(BigInteger.valueOf(1_500_000), request.amount)
        assertEquals(LOWERCASE_0X, request.to)
    }

    private fun form(
        mode: PrivateUsdSendMode,
        recipient: String,
        amount: BigDecimal = BigDecimal.ONE,
    ) = PrivateUsdSendForm(
        mode = mode,
        amount = NumberTextFieldInnerState.fromAmount(amount),
        recipient = recipient,
    )

    private companion object {
        val asset =
            PrivateUsdAsset(
                token = checkNotNull(PrivateUsdTokens.find(RailgunNetwork.SEPOLIA, TEST_USD)),
                available = BigInteger.valueOf(12_340_000),
            )
        val ZERO_K = "0zk1" + "qyjqfvr4".repeat(15)
        const val TEST_USD = "0x5764D0044bef5AA839E0dDafE2073421101B9Ed8"
        const val LOWERCASE_0X = "0x09ed1f966745be18c711c346242c0974dad7c3e5"
        const val CHECKSUMMED_0X = "0x09eD1F966745Be18C711C346242c0974DAd7c3e5"
        const val MISTYPED_0X = "0x09Ed1F966745Be18C711C346242c0974DAd7c3e5"
    }
}
