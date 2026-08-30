// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.onramp

import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.math.bigIntegerValueOf
import xyz.justzappit.evm.math.div
import xyz.justzappit.evm.math.minus
import xyz.justzappit.evm.math.plus
import xyz.justzappit.evm.math.times
import xyz.justzappit.offramp.p2p.CurrencyCode
import xyz.justzappit.offramp.p2p.P2pOrderLimits
import xyz.justzappit.offramp.p2p.PriceConfig
import xyz.justzappit.offramp.p2p.Usdc6

/**
 * What one BUY costs and what it may claim, in the Diamond's own integer arithmetic.
 *
 * Kept apart from the driver because every number here is checked against the contract rather than
 * against a service, and getting one wrong is not a crash: the order places, routes, and is then
 * refused by merchants or reverted on a price tick.
 */
data class DirectBuyQuote(
    /** What the user hands the merchant, before anything is deducted. */
    val fiatAmount: Usdc6,
    /** USDC the fiat buys at the current buy price, before the fee. */
    val grossUsdc: Usdc6,
    val feeUsdc: Usdc6,
    /** What is actually placed: BUY subtracts the fee client-side and places the net. */
    val netUsdc: Usdc6,
    val buyPrice: Usdc6,
)

object DirectOnrampPricing {
    /**
     * Fee direction is opposite on the two sides of the exchange, and the difference is not
     * cosmetic: **BUY** subtracts the fee client-side and places the net, while **SELL/PAY** places
     * the gross and lets the Diamond pull the fee as a second `transferFrom` inside
     * `setSellOrderUpi`. Placing the gross on a BUY overstates what the merchant owes.
     */
    fun feeFor(grossUsdc: Usdc6, threshold: Usdc6, fixedFeeBuy: Usdc6): Usdc6 =
        if (grossUsdc <= threshold) fixedFeeBuy else Usdc6.ZERO

    fun quote(
        fiatAmount: Usdc6,
        price: PriceConfig,
        threshold: Usdc6,
        fixedFeeBuy: Usdc6,
    ): DirectBuyQuote {
        require(price.buyPrice.micros.signum() > 0) { "buy price is unreadable for this corridor" }
        require(fiatAmount.micros.signum() > 0) { "fiat amount must be positive" }
        val gross = Usdc6(fiatAmount.micros * MICROS / price.buyPrice.micros)
        val fee = feeFor(gross, threshold, fixedFeeBuy)
        require(gross > fee) { "order is smaller than the fixed fee it would pay" }
        val net = Usdc6(gross.micros - fee.micros)
        return DirectBuyQuote(
            fiatAmount = fiatAmount,
            grossUsdc = gross,
            feeUsdc = fee,
            netUsdc = net,
            buyPrice = price.buyPrice,
        )
    }

    /**
     * `0` disables the check on chain, which lets a merchant fill at any rate — so it is never
     * passed. Merchants read this as the order's rate and skip anything off market.
     */
    fun fiatAmountLimit(netUsdc: Usdc6, buyPrice: Usdc6): Usdc6 =
        Usdc6(netUsdc.micros * buyPrice.micros / MICROS)

    /**
     * The corridor's bounds for this wallet, in fiat.
     *
     * The ceiling is the Diamond's own `userTxLimit(user, currency).buy` — the effective number,
     * and the reason a cold wallet must never reach an amount field at all. Zapp's own
     * [P2pOrderLimits.MAX_ORDER] caps it further; that limit is ours, not the protocol's.
     *
     * The floor is one dollar of USDC on top of the fixed fee: below that the fee is most of the
     * order, and the user pays real money for almost nothing.
     */
    fun limitsFor(
        buyLimit: Usdc6,
        price: PriceConfig,
        fixedFeeBuy: Usdc6,
        enabled: Boolean,
        currency: CurrencyCode,
    ): OnrampLimits {
        if (!enabled || price.buyPrice.micros.signum() <= 0 || buyLimit.micros.signum() <= 0) {
            return OnrampLimits.DISABLED.copy(currency = currency)
        }
        val cappedUsdc = if (buyLimit > P2pOrderLimits.MAX_ORDER) P2pOrderLimits.MAX_ORDER else buyLimit
        val minUsdc = Usdc6(fixedFeeBuy.micros + MICROS)
        val maxFiat = fiatAmountLimit(cappedUsdc, price.buyPrice)
        val minFiat = fiatAmountLimit(minUsdc, price.buyPrice)
        return OnrampLimits(
            enabled = minFiat < maxFiat,
            currency = currency,
            minFiat = minFiat,
            maxFiat = maxFiat,
            // Nothing on chain caps a day's buying beyond the per-order limit, so claiming a
            // separate daily figure would be inventing one.
            perUserDailyFiat = maxFiat,
        )
    }

    /** One whole unit in the 6-decimal fixed-point the Diamond prices everything in. */
    private const val MICROS_PER_UNIT = 1_000_000L
    private val MICROS: BigInteger = bigIntegerValueOf(MICROS_PER_UNIT)
}
