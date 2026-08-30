// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.onramp

import xyz.justzappit.offramp.p2p.CurrencyCode
import xyz.justzappit.offramp.p2p.PriceConfig
import xyz.justzappit.offramp.p2p.Usdc6
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Every figure below comes from INR on Base mainnet, read 2026-08-29: buy 100.46, sell 96.52,
 * small-order threshold $10, fixed buy fee $0.05.
 */
class DirectOnrampPricingTest {
    @Test
    fun `a small order pays the fixed fee and places the net`() {
        val quote = DirectOnrampPricing.quote(Usdc6.ofMicros(500_000_000L), INR_PRICE, THRESHOLD, FEE)
        // ₹500 at 100.46 buys 4.977105 USDC.
        assertEquals(Usdc6.ofMicros(4_977_105L), quote.grossUsdc)
        assertEquals(FEE, quote.feeUsdc)
        // BUY subtracts client-side: the placed amount is the net, not the gross.
        assertEquals(Usdc6.ofMicros(4_927_105L), quote.netUsdc)
    }

    @Test
    fun `an order above the threshold pays no fixed fee`() {
        // $10 threshold: ₹2000 is about $19.9, so the fee falls away entirely.
        val quote = DirectOnrampPricing.quote(Usdc6.ofMicros(2_000_000_000L), INR_PRICE, THRESHOLD, FEE)
        assertEquals(Usdc6.ZERO, quote.feeUsdc)
        assertEquals(quote.grossUsdc, quote.netUsdc)
    }

    @Test
    fun `fiatAmountLimit is the contract's own arithmetic on the net`() {
        val quote = DirectOnrampPricing.quote(Usdc6.ofMicros(500_000_000L), INR_PRICE, THRESHOLD, FEE)
        val limit = DirectOnrampPricing.fiatAmountLimit(quote.netUsdc, quote.buyPrice)
        // netUsdc × buyPrice ÷ 1e6, integer division and all — a merchant reads this as the rate.
        assertEquals(Usdc6.ofMicros(4_927_105L * 100_460_000L / 1_000_000L), limit)
        // Never zero: zero disables the check and lets a merchant fill at any rate.
        assertTrue(limit.micros.signum() > 0)
    }

    @Test
    fun `an order smaller than its own fee is refused before it reaches the chain`() {
        // ₹4 buys about $0.04, less than the $0.05 fee.
        assertFailsWith<IllegalArgumentException> {
            DirectOnrampPricing.quote(Usdc6.ofMicros(4_000_000L), INR_PRICE, THRESHOLD, FEE)
        }
    }

    @Test
    fun `the ceiling is the wallet's own on-chain buy limit`() {
        val limits =
            DirectOnrampPricing.limitsFor(
                buyLimit = Usdc6.ofMicros(50_000_000L),
                price = INR_PRICE,
                fixedFeeBuy = FEE,
                enabled = true,
                currency = CurrencyCode.Inr,
            )
        assertTrue(limits.enabled)
        // $50 at 100.46 — what the Diamond will actually let this wallet place.
        assertEquals(Usdc6.ofMicros(50_000_000L * 100_460_000L / 1_000_000L), limits.maxFiat)
        // Direct orders have a per-transaction ceiling, not an invented daily allowance.
        assertEquals(Usdc6.ZERO, limits.perUserDailyFiat)
        // A dollar of USDC on top of the fee, so the fee is never most of the order.
        assertEquals(Usdc6.ofMicros(1_050_000L * 100_460_000L / 1_000_000L), limits.minFiat)
    }

    @Test
    fun `Zapp's own hundred-dollar cap still applies above it`() {
        val limits =
            DirectOnrampPricing.limitsFor(
                buyLimit = Usdc6.ofMicros(400_000_000L),
                price = INR_PRICE,
                fixedFeeBuy = FEE,
                enabled = true,
                currency = CurrencyCode.Inr,
            )
        // The wallet may buy $400 on chain; this app does not offer more than $100 in one order.
        assertEquals(Usdc6.ofMicros(100_000_000L * 100_460_000L / 1_000_000L), limits.maxFiat)
    }

    @Test
    fun `a cold wallet gets a disabled corridor, not a zero-width one`() {
        val limits =
            DirectOnrampPricing.limitsFor(
                buyLimit = Usdc6.ZERO,
                price = INR_PRICE,
                fixedFeeBuy = FEE,
                enabled = true,
                currency = CurrencyCode.Inr,
            )
        assertFalse(limits.enabled)
        assertEquals(CurrencyCode.Inr, limits.currency)
    }

    @Test
    fun `a paused exchange disables the corridor whatever the wallet can do`() {
        val limits =
            DirectOnrampPricing.limitsFor(
                buyLimit = Usdc6.ofMicros(400_000_000L),
                price = INR_PRICE,
                fixedFeeBuy = FEE,
                enabled = false,
                currency = CurrencyCode.Inr,
            )
        assertFalse(limits.enabled)
    }

    private companion object {
        val INR_PRICE = PriceConfig(sellPrice = Usdc6.ofMicros(96_520_000L), buyPrice = Usdc6.ofMicros(100_460_000L))
        val THRESHOLD = Usdc6.ofMicros(10_000_000L)
        val FEE = Usdc6.ofMicros(50_000L)
    }
}
