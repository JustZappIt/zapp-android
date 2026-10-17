// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd.convert

import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapQuote
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapRepository
import co.electriccoin.zcash.ui.common.atomicswap.DepositFee
import co.electriccoin.zcash.ui.common.atomicswap.ZIP317_MIN_FEE_ZAT
import xyz.justzappit.offramp.atomicswap.AtomicSwapBlock
import xyz.justzappit.offramp.atomicswap.AtomicSwapBlockedException
import xyz.justzappit.offramp.atomicswap.AtomicSwapOffer
import xyz.justzappit.offramp.p2p.Usdc6
import java.math.BigInteger
import kotlin.time.Clock

/** An offer the wallet can pay now: its deposit and the network fee on it, capped at what they come to. */
internal data class ZecQuote(
    val offer: AtomicSwapOffer,
    val feeZat: Long,
) {
    val totalZat: Long get() = offer.quote.depositZat + feeZat
}

/** Every quote spends a swap index, so the latest prices the next while it holds: an amount usually takes one. */
internal class PrivateUsdZecQuotes(
    private val repository: AtomicSwapRepository,
    private val nowSeconds: () -> Long = { Clock.System.now().epochSeconds },
) {
    private var latest: AtomicSwapQuote? = null

    /**
     * The dearest quote within [totalZat]; [requested] goes first when a quote for it just ran out. The maker refuses
     * an amount it doesn't convert.
     */
    suspend fun quote(
        totalZat: Long,
        requested: Usdc6? = null
    ): ZecQuote {
        var amount = requested ?: basis().let { amountFor(totalZat - (it.depositFeeZat ?: ZIP317_MIN_FEE_ZAT), it) }
        repeat(MAX_QUOTES) {
            val quote = repository.quote(amount).also { latest = it }
            val shortZat = (quote.depositFee as? DepositFee.Short)?.zat
            // Short: spending the whole balance takes more notes, so a dearer fee, than the last quote's deposit did.
            val depositZat =
                if (shortZat != null) {
                    quote.offer.quote.depositZat - shortZat
                } else {
                    val payable = quote.payable()
                    if (payable.totalZat <= totalZat) return payable
                    totalZat - payable.feeZat
                }
            amount = amountFor(depositZat, quote, lessThan = amount)
        }
        throw ZecInputQuoteException()
    }

    // The latest quote while it holds; otherwise one for [PRICE_PROBE], to price the next.
    private suspend fun basis(): AtomicSwapQuote =
        latest?.takeIf { it.offer.quote.expiresAt > nowSeconds() }
            ?: repository.quote(PRICE_PROBE).also { latest = it }

    // What [basis]'s price buys with a [depositZat] deposit. A deposit pays the maker's network cost on top of the
    // amount, at the same price.
    private fun amountFor(
        depositZat: Long,
        basis: AtomicSwapQuote,
        lessThan: Usdc6? = null,
    ): Usdc6 {
        val quote = basis.offer.quote
        val cost = quote.networkCost?.micros ?: BigInteger.ZERO
        val deposit = depositZat.coerceAtLeast(0).toBigInteger()
        val priced = deposit * (quote.amount.micros + cost) / quote.depositZat.toBigInteger() - cost
        val amount = lessThan?.let { priced.min(it.micros.dec()) } ?: priced
        if (amount.signum() <= 0) throw ZecInputQuoteException()
        return Usdc6(amount)
    }

    private fun AtomicSwapQuote.payable(): ZecQuote {
        val feeZat = depositFeeZat ?: throw depositFee.unpayable()
        return ZecQuote(offer.copy(maxTotalZat = offer.quote.depositZat + feeZat), feeZat)
    }

    private fun DepositFee.unpayable(): Exception =
        when (this) {
            DepositFee.Split -> AtomicSwapBlockedException(AtomicSwapBlock.DEPOSIT_UNPAYABLE, "split")
            DepositFee.Unready -> AtomicSwapBlockedException(AtomicSwapBlock.ZCASH_UNAVAILABLE, "unready")
            is DepositFee.Payable, is DepositFee.Short -> ZecInputQuoteException()
        }

    companion object {
        /** Quoted for the maker's price while no quote holds: a dollar's deposit prices the next to the zatoshi. */
        val PRICE_PROBE: Usdc6 = Usdc6.ofMicros(1_000_000)

        private const val MAX_QUOTES = 3
    }
}

internal class ZecInputQuoteException : Exception("no quote fits the entered ZEC amount")
