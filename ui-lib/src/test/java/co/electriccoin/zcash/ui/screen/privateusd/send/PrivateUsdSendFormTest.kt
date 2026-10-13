// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd.send

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdAsset
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdSendCost
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdTokens
import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.stringResByQuantity
import xyz.justzappit.evm.types.Address
import xyz.justzappit.railgun.RailgunDestination
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
        assertEquals(
            stringRes(R.string.private_usd_send_invalid_0zk),
            form(PrivateUsdSendMode.PRIVATE, "0zk1abc").recipientError(),
        )
    }

    @Test
    fun `a railgun address with a mistyped character fails its checksum before anything is proved`() {
        val typo = ZERO_K.replaceRange(TYPO_AT, TYPO_AT + 1, if (ZERO_K[TYPO_AT] == 'q') "p" else "q")

        assertNull(form(PrivateUsdSendMode.PRIVATE, typo).request(asset))
        assertEquals(
            stringRes(R.string.private_usd_send_invalid_0zk),
            form(PrivateUsdSendMode.PRIVATE, typo).recipientError(),
        )
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
    fun `nothing is withdrawn to the zero address, which says why`() {
        val zero = form(PrivateUsdSendMode.WITHDRAW, Address.ZERO.lowercaseHex)

        assertNull(zero.request(asset))
        assertEquals(stringRes(R.string.private_usd_send_zero_address), zero.recipientError())
    }

    @Test
    fun `nothing more than is available can be sent`() {
        val tooMuch = form(PrivateUsdSendMode.PRIVATE, ZERO_K, BigDecimal("12.35"))

        assertEquals(stringRes(R.string.private_usd_send_too_much), tooMuch.amountError(asset))
        assertNull(tooMuch.request(asset))
        assertNull(form(PrivateUsdSendMode.PRIVATE, ZERO_K, BigDecimal("12.34")).amountError(asset))
    }

    @Test
    fun `an amount finer than the token goes is refused, not rounded down`() {
        val tooPrecise = form(PrivateUsdSendMode.PRIVATE, ZERO_K, BigDecimal("1.0000001"))

        assertNull(tooPrecise.request(asset))
        assertEquals(stringResByQuantity(R.plurals.private_usd_send_too_precise, 6), tooPrecise.amountError(asset))
        assertNotNull(form(PrivateUsdSendMode.PRIVATE, ZERO_K, BigDecimal("1.000001")).request(asset))
    }

    @Test
    fun `nothing is sent for zero, which is still being typed, so isn't flagged`() {
        val zero = form(PrivateUsdSendMode.PRIVATE, ZERO_K, BigDecimal.ZERO)

        assertNull(zero.request(asset))
        assertNull(zero.amountError(asset))
    }

    @Test
    fun `the request carries the amount in base units and a trimmed recipient`() {
        val form = form(PrivateUsdSendMode.WITHDRAW, " $LOWERCASE_0X\n", BigDecimal("1.5"))
        val request = checkNotNull(form.request(asset))

        assertEquals(BigInteger.valueOf(1_500_000), request.amount)
        assertEquals(RailgunDestination.Public(Address.parse(LOWERCASE_0X)), request.to)
    }

    @Test
    fun `the review shows every digit, so the fee and what's received add up to what's sent`() {
        val form = form(PrivateUsdSendMode.WITHDRAW, LOWERCASE_0X, BigDecimal.ONE)
        val request = checkNotNull(form.request(asset))
        val cost = PrivateUsdSendCost(BigInteger.valueOf(2_500), 25, BigInteger.ZERO, asset.token.address)
        val review = checkNotNull(form.copy(cost = cost).review(request))

        val sent = review.amount.number()
        val fee = checkNotNull(review.railgunFee).amount.number()
        val received = review.receives.number()
        assertEquals(BigDecimal("0.0025"), fee.stripTrailingZeros())
        assertEquals(sent, fee + received)
        assertEquals(asset.token.symbol, review.token)
    }

    private fun StringResource.number(): BigDecimal {
        val amount = this as StringResource.ByCurrencyNumber
        assertEquals(asset.token.decimals, amount.maxDecimals)
        return amount.amount as BigDecimal
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
                token = checkNotNull(PrivateUsdTokens.find(RailgunNetwork.SEPOLIA, Address.parse(TEST_USD))),
                available = BigInteger.valueOf(12_340_000),
            )
        const val TYPO_AT = 40

        // Railgun wallet 0 of a seed of 64 bytes of 0x08, as zecSwap's vectors derive it.
        const val ZERO_K =
            "0zk1qyrs4qyrd08p6uep0fc2y8njktgcpezts3rpaq6q0ln948ecjkw8prv7j6f" +
                "e3z53llz8ursderja0juwv5pgnv8x5klmmwkv8q38h9n704h4d4qjyw7n5qk68nx"
        const val TEST_USD = "0x5764D0044bef5AA839E0dDafE2073421101B9Ed8"
        const val LOWERCASE_0X = "0x09ed1f966745be18c711c346242c0974dad7c3e5"
        const val CHECKSUMMED_0X = "0x09eD1F966745Be18C711C346242c0974DAd7c3e5"
        const val MISTYPED_0X = "0x09Ed1F966745Be18C711C346242c0974DAd7c3e5"
    }
}
