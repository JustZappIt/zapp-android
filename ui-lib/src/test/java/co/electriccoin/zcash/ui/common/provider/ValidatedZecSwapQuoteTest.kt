// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.provider

import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.model.DynamicSwapAddress
import co.electriccoin.zcash.ui.common.model.DynamicSwapAsset
import co.electriccoin.zcash.ui.common.model.SwapAsset
import co.electriccoin.zcash.ui.common.model.SwapBlockchain
import co.electriccoin.zcash.ui.common.model.SwapMode
import co.electriccoin.zcash.ui.common.model.SwapQuote
import co.electriccoin.zcash.ui.common.model.ZecSwapAsset
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.imageRes
import xyz.justzappit.evm.types.Address
import xyz.justzappit.offramp.onramp.OnrampZecDeliveryCheckpoint
import xyz.justzappit.offramp.onramp.OnrampZecDeliveryPhase
import xyz.justzappit.offramp.p2p.Usdc6
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.time.Instant

class ValidatedZecSwapQuoteTest {
    @Test
    fun `valid exact-input quote returns normalized recovery handles`() {
        val validated = validateZecSwapQuote(quote(), AMOUNT, ACCOUNT, ZCASH_RECIPIENT, SLIPPAGE, NOW)

        assertEquals(DEPOSIT, validated.depositAddress)
        assertEquals(ZCASH_RECIPIENT, validated.zcashRecipient)
        assertEquals(DEADLINE.toEpochMilliseconds(), validated.deadlineMillis)
        assertEquals("1900000", validated.outputZec)
        assertEquals(INPUT_USD, validated.inputUsd)
        assertEquals(OUTPUT_USD, validated.outputUsd)
    }

    @Test
    fun `amount address output slippage and deadline mismatches fail closed`() {
        assertFailsWith<IllegalArgumentException> {
            validateZecSwapQuote(quote(amount = BigDecimal("0.9")), AMOUNT, ACCOUNT, ZCASH_RECIPIENT, SLIPPAGE, NOW)
        }
        assertFailsWith<IllegalArgumentException> {
            validateZecSwapQuote(quote(refund = OTHER_ACCOUNT), AMOUNT, ACCOUNT, ZCASH_RECIPIENT, SLIPPAGE, NOW)
        }
        assertFailsWith<IllegalArgumentException> {
            validateZecSwapQuote(quote(recipient = "u1other"), AMOUNT, ACCOUNT, ZCASH_RECIPIENT, SLIPPAGE, NOW)
        }
        assertFailsWith<IllegalArgumentException> {
            validateZecSwapQuote(quote(output = BigDecimal.ZERO), AMOUNT, ACCOUNT, ZCASH_RECIPIENT, SLIPPAGE, NOW)
        }
        assertFailsWith<IllegalArgumentException> {
            validateZecSwapQuote(quote(slippage = BigDecimal("2")), AMOUNT, ACCOUNT, ZCASH_RECIPIENT, SLIPPAGE, NOW)
        }
        assertFailsWith<IllegalArgumentException> {
            validateZecSwapQuote(quote(deadline = NOW), AMOUNT, ACCOUNT, ZCASH_RECIPIENT, SLIPPAGE, NOW)
        }
        assertFailsWith<IllegalArgumentException> {
            validateZecSwapQuote(
                quote(deadline = Instant.fromEpochMilliseconds(NOW.toEpochMilliseconds() + 60_000L)),
                AMOUNT,
                ACCOUNT,
                ZCASH_RECIPIENT,
                SLIPPAGE,
                NOW,
            )
        }
    }

    @Test
    fun `a status echoing this order's route is accepted`() {
        validateZecSwapStatus(quote(), checkpoint())
    }

    @Test
    fun `a status echoing a substituted route is rejected before its verdict is believed`() {
        assertFailsWith<IllegalArgumentException> {
            validateZecSwapStatus(quote(deposit = OTHER_ACCOUNT), checkpoint())
        }
        assertFailsWith<IllegalArgumentException> {
            validateZecSwapStatus(quote(recipient = "u1other"), checkpoint())
        }
        assertFailsWith<IllegalArgumentException> {
            validateZecSwapStatus(quote(refund = OTHER_ACCOUNT), checkpoint())
        }
        assertFailsWith<IllegalArgumentException> {
            validateZecSwapStatus(quote(amount = BigDecimal("0.9")), checkpoint())
        }
    }

    @Test
    fun `USDC is selected by contract address and ZEC by asset type`() {
        val usdc = asset(assetId = "nep141:base-${USDC.lowercaseHex.removePrefix("0x")}.omft.near")
        val other = asset(assetId = "nep141:base-00000000000000000000000000000000000000ff.omft.near")

        assertSame(usdc, listOf(other, usdc).usdcAsset(USDC))
        assertSame(ZEC, listOf(other, ZEC).zecAsset())
    }

    @Test
    fun `a catalog missing either side of the route fails closed`() {
        assertFailsWith<IllegalStateException> { listOf<SwapAsset>().usdcAsset(USDC) }
        assertFailsWith<IllegalStateException> { listOf<SwapAsset>().zecAsset() }
    }

    private fun checkpoint() =
        OnrampZecDeliveryCheckpoint(
            phase = OnrampZecDeliveryPhase.AWAITING_ZEC,
            usdcMicros = AMOUNT.micros.toString(),
            baseAccount = ACCOUNT.checksumHex,
            zcashRecipient = ZCASH_RECIPIENT,
            depositAddress = DEPOSIT.checksumHex,
            quoteDeadlineMillis = DEADLINE.toEpochMilliseconds(),
            transferStarted = true,
            userOperationHash = "0xuser-operation",
            baseTransactionHash = "0xbase-transaction",
        )

    private fun quote(
        amount: BigDecimal = AMOUNT.whole,
        refund: Address = ACCOUNT,
        recipient: String = ZCASH_RECIPIENT,
        deposit: Address = DEPOSIT,
        output: BigDecimal = BigDecimal("1900000"),
        slippage: BigDecimal = SLIPPAGE,
        deadline: Instant = DEADLINE,
    ): SwapQuote =
        TestSwapQuote(
            amountInFormatted = amount,
            refundAddress = DynamicSwapAddress(refund.checksumHex),
            destinationAddress = DynamicSwapAddress(recipient),
            depositAddress = DynamicSwapAddress(deposit.checksumHex),
            amountOut = output,
            slippage = slippage,
            deadline = deadline,
        )

    private data class TestSwapQuote(
        override val amountInFormatted: BigDecimal,
        override val refundAddress: DynamicSwapAddress,
        override val destinationAddress: DynamicSwapAddress,
        override val depositAddress: DynamicSwapAddress,
        override val amountOut: BigDecimal,
        override val slippage: BigDecimal,
        override val deadline: Instant,
    ) : SwapQuote {
        override val originAsset: SwapAsset = asset()
        override val destinationAsset: SwapAsset = asset()
        override val provider: String = "test"
        override val mode: SwapMode = SwapMode.EXACT_INPUT
        override val zecExchangeRate: BigDecimal = BigDecimal.ONE
        override val amountIn: BigDecimal = amountInFormatted.movePointRight(USDC_DECIMALS)
        override val amountInUsd: BigDecimal = INPUT_USD
        override val amountOutUsd: BigDecimal = OUTPUT_USD
        override val amountOutFormatted: BigDecimal = amountOut
        override val affiliateFee: BigDecimal = BigDecimal.ZERO
        override val affiliateFeeZatoshi: Zatoshi = Zatoshi(0)
        override val affiliateFeeUsd: BigDecimal = BigDecimal.ZERO
        override val timestamp: Instant = NOW
        override val estimatedDurationSeconds: Int? = null

        override fun getTotal(proposal: Proposal?): BigDecimal = BigDecimal.ZERO

        override fun getTotalUsd(proposal: Proposal?): BigDecimal = BigDecimal.ZERO

        override fun getTotalFeesUsd(proposal: Proposal?): BigDecimal = BigDecimal.ZERO

        override fun getTotalFeesZatoshi(proposal: Proposal?): Zatoshi = Zatoshi(0)
    }

    private companion object {
        const val USDC_DECIMALS = 6
        const val ZCASH_RECIPIENT = "u1test-recipient"
        val ACCOUNT: Address = Address.parse("0x0000000000000000000000000000000000000001")
        val OTHER_ACCOUNT: Address = Address.parse("0x0000000000000000000000000000000000000002")
        val DEPOSIT: Address = Address.parse("0x0000000000000000000000000000000000000003")
        val USDC: Address = Address.parse("0x0000000000000000000000000000000000000004")
        val AMOUNT: Usdc6 = Usdc6.ofMicros(910_153)
        val INPUT_USD: BigDecimal = BigDecimal("0.91")
        val OUTPUT_USD: BigDecimal = BigDecimal("0.85")
        val SLIPPAGE: BigDecimal = BigDecimal("1")
        val NOW: Instant = Instant.fromEpochMilliseconds(1_800_000_000_000)
        val DEADLINE: Instant = Instant.fromEpochMilliseconds(1_800_007_200_000)

        val BLOCKCHAIN =
            SwapBlockchain(
                chainTicker = "base",
                chainName = StringResource.ByString("Base"),
                chainIcon = imageRes("base"),
            )

        val ZEC =
            ZecSwapAsset(
                tokenTicker = "ZEC",
                tokenName = StringResource.ByString("Zcash"),
                tokenIcon = imageRes("zec"),
                blockchain = BLOCKCHAIN,
                usdPrice = null,
                assetId = "nep141:zec.omft.near",
                decimals = 8,
            )

        fun asset(assetId: String = "usdc") =
            DynamicSwapAsset(
                tokenTicker = "USDC",
                tokenName = StringResource.ByString("USDC"),
                tokenIcon = imageRes("usdc"),
                usdPrice = null,
                assetId = assetId,
                decimals = USDC_DECIMALS,
                blockchain = BLOCKCHAIN,
            )
    }
}
