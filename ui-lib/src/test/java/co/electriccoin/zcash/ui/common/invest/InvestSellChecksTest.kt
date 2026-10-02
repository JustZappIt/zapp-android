package co.electriccoin.zcash.ui.common.invest

import co.electriccoin.zcash.ui.common.invest.model.InvestAssets
import co.electriccoin.zcash.ui.common.invest.model.SellAmount
import co.electriccoin.zcash.ui.common.invest.model.SellEstimate
import co.electriccoin.zcash.ui.common.invest.repository.InvestSellChecks
import co.electriccoin.zcash.ui.common.invest.repository.InvestSellChecks.Resolved
import co.electriccoin.zcash.ui.common.model.near.QuoteDetails
import co.electriccoin.zcash.ui.common.model.near.QuoteRequest
import co.electriccoin.zcash.ui.common.model.near.QuoteResponseDto
import co.electriccoin.zcash.ui.common.model.near.RecipientType
import co.electriccoin.zcash.ui.common.model.near.RefundType
import co.electriccoin.zcash.ui.common.model.near.SwapType
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

@Suppress("MaxLineLength")
class InvestSellChecksTest {
    private val nvda = InvestAssets.curated.first { it.ticker == "NVDA" }
    private val price = BigDecimal("224.46")
    private val held = BigInteger("440974000000000000")

    @Test
    fun `a sale of exactly the minimum passes although base units round it down`() {
        val order = assertIs<Resolved.Order>(InvestSellChecks.resolve(SellAmount.Usd(BigDecimal(40)), held, 18, price))

        assertTrue(order.usdValue!! < BigDecimal(40))
        InvestSellChecks.requireEcho(response(order, amountInUsd = order.usdValue), nvda, order, ACCOUNT, PAYOUT)
    }

    @Test
    fun `a partial sale under the minimum is refused, selling everything is not`() {
        assertEquals(
            Resolved.Refused(SellEstimate.BelowMinimum(BigDecimal(40))),
            InvestSellChecks.resolve(SellAmount.Usd(BigDecimal("39.99")), held, 18, price),
        )
        assertEquals(
            Resolved.Refused(SellEstimate.BelowMinimum(BigDecimal(40))),
            InvestSellChecks.resolve(SellAmount.Units(BigDecimal("0.1")), held, 18, price),
        )
        val small = BigInteger("100000000000000000")
        assertIs<Resolved.Order>(InvestSellChecks.resolve(SellAmount.All, small, 18, price))
    }

    @Test
    fun `without a price a dollar amount can't be resolved and shares are held to the quote`() {
        assertEquals(
            Resolved.Refused(SellEstimate.NoPrice),
            InvestSellChecks.resolve(SellAmount.Usd(BigDecimal(50)), held, 18, null),
        )
        val order = assertIs<Resolved.Order>(InvestSellChecks.resolve(SellAmount.Units(BigDecimal("0.1")), held, 18, null))

        assertFailsWith<IllegalArgumentException> {
            InvestSellChecks.requireEcho(response(order, amountInUsd = BigDecimal("22.45")), nvda, order, ACCOUNT, PAYOUT)
        }
        InvestSellChecks.requireEcho(response(order, amountInUsd = BigDecimal("40.10")), nvda, order, ACCOUNT, PAYOUT)
    }

    @Test
    fun `nothing held, too much, and zero base units are refused`() {
        assertEquals(Resolved.Refused(SellEstimate.NothingHeld), InvestSellChecks.resolve(SellAmount.All, BigInteger.ZERO, 18, price))
        assertEquals(
            Resolved.Refused(SellEstimate.ExceedsHolding(BigDecimal("0.440974000000000000"))),
            InvestSellChecks.resolve(SellAmount.Units(BigDecimal("0.5")), held, 18, price),
        )
        assertEquals(
            Resolved.Refused(SellEstimate.BelowMinimum(BigDecimal(40))),
            InvestSellChecks.resolve(SellAmount.Units(BigDecimal("1e-19")), held, 18, null),
        )
    }

    @Test
    fun `the quote must keep the slippage asked for and a fair ZEC return`() {
        val order = assertIs<Resolved.Order>(InvestSellChecks.resolve(SellAmount.All, held, 18, price))
        val fair = response(order)
        InvestSellChecks.requireEcho(fair, nvda, order, ACCOUNT, PAYOUT)

        listOf(
            fair.copy(quoteRequest = fair.quoteRequest.copy(slippageTolerance = 500)),
            fair.copy(quote = fair.quote.copy(minAmountOut = BigDecimal(6_000_000))),
            fair.copy(quote = fair.quote.copy(amountInUsd = BigDecimal("120"))),
        ).forEach { tampered ->
            assertFailsWith<IllegalArgumentException> { InvestSellChecks.requireEcho(tampered, nvda, order, ACCOUNT, PAYOUT) }
        }

        val partial = assertIs<Resolved.Order>(InvestSellChecks.resolve(SellAmount.Usd(BigDecimal(50)), held, 18, price))
        assertFailsWith<IllegalArgumentException> {
            InvestSellChecks.requireEcho(response(partial, amountOutUsd = BigDecimal("40")), nvda, partial, ACCOUNT, PAYOUT)
        }
    }

    @Test
    fun `1Click's floor, rounded down to a whole zat, is within the slippage`() {
        val order = assertIs<Resolved.Order>(InvestSellChecks.resolve(SellAmount.All, held, 18, price))
        val fair = response(order)
        // A real pair from 2026-09-25: 6342045 × 0.99 = 6278624.55, and 1Click promised 6278624.
        val real = fair.copy(quote = fair.quote.copy(amountOut = BigDecimal(6_342_045), minAmountOut = BigDecimal(6_278_624)))
        InvestSellChecks.requireEcho(real, nvda, order, ACCOUNT, PAYOUT)

        val short = real.copy(quote = real.quote.copy(minAmountOut = BigDecimal(6_278_623)))
        assertFailsWith<IllegalArgumentException> { InvestSellChecks.requireEcho(short, nvda, order, ACCOUNT, PAYOUT) }
    }

    @Test
    fun `an amount 1Click can't cover its fees for is recognised`() {
        assertTrue(InvestSellChecks.isAmountTooSmall(400, "Quote error. INSUFFICIENT_AMOUNT"))
        assertTrue(InvestSellChecks.isAmountTooSmall(400, "Amount is too low for bridge, try at least 1000000"))
        assertFalse(InvestSellChecks.isAmountTooSmall(400, "tokenIn is not valid"))
        assertFalse(InvestSellChecks.isAmountTooSmall(500, "INSUFFICIENT_AMOUNT"))
    }

    @Test
    fun `only a refusal that proves nothing ran is definite`() {
        assertTrue(InvestSellChecks.isDefiniteRefusal(429, null))
        assertTrue(InvestSellChecks.isDefiniteRefusal(400, "Invalid signature"))
        assertTrue(InvestSellChecks.isDefiniteRefusal(400, "Deadline has expired"))
        assertTrue(InvestSellChecks.isDefiniteRefusal(400, "Intent refused: invalid signature"))
        assertFalse(InvestSellChecks.isDefiniteRefusal(400, "Nonce already used"))
        assertFalse(InvestSellChecks.isDefiniteRefusal(400, "Duplicate signature"))
        assertFalse(InvestSellChecks.isDefiniteRefusal(400, "Signature already used"))
        assertFalse(InvestSellChecks.isDefiniteRefusal(400, "Duplicated signature"))
        assertFalse(InvestSellChecks.isDefiniteRefusal(400, "Signature reused"))
        assertTrue(InvestSellChecks.isDefiniteRefusal(400, "Invalid signature for nonexistent account"))
        assertFalse(InvestSellChecks.isDefiniteRefusal(400, "Insufficient balance"))
        assertFalse(InvestSellChecks.isDefiniteRefusal(400, null))
        assertFalse(InvestSellChecks.isDefiniteRefusal(500, "signature"))
        assertFalse(InvestSellChecks.isDefiniteRefusal(null, null))
    }

    private fun response(
        order: Resolved.Order,
        amountInUsd: BigDecimal = order.units.multiply(price),
        amountOutUsd: BigDecimal = amountInUsd.multiply(BigDecimal("0.98")),
    ) = QuoteResponseDto(
        timestamp = NOW,
        quoteRequest =
            QuoteRequest(
                dry = false,
                swapType = SwapType.EXACT_INPUT,
                slippageTolerance = 100,
                originAsset = nvda.assetId,
                depositType = RefundType.CONFIDENTIAL_INTENTS,
                destinationAsset = "nep141:zec.omft.near",
                amount = BigDecimal(order.baseUnits),
                refundTo = ACCOUNT,
                refundType = RefundType.CONFIDENTIAL_INTENTS,
                recipient = PAYOUT,
                recipientType = RecipientType.DESTINATION_CHAIN,
                deadline = NOW,
                appFees = emptyList(),
            ),
        quote =
            QuoteDetails(
                depositAddress = "deposit",
                amountIn = BigDecimal(order.baseUnits),
                amountInFormatted = order.units,
                amountInUsd = amountInUsd,
                minAmountIn = BigDecimal(order.baseUnits),
                amountOut = BigDecimal(6_340_000),
                amountOutFormatted = BigDecimal("0.0634"),
                amountOutUsd = amountOutUsd,
                minAmountOut = BigDecimal(6_280_000),
                deadline = NOW,
                timeEstimate = 140,
            ),
    )

    private companion object {
        const val ACCOUNT = "0x9858effd232b4033e47d90003d41ec34ecaeda94"
        const val PAYOUT = "u1freshpayoutaddress"
        val NOW = Instant.parse("2026-09-28T14:00:00Z")
    }
}
