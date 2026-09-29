// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd.convert

import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapQuote
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapRepository
import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
import java.math.BigDecimal

internal class PrivateUsdZecQuotes(
    private val repository: AtomicSwapRepository
) {
    private val deployment = checkNotNull(repository.deployment)
    private var reference: AtomicSwapQuote? = null

    suspend fun quote(totalZat: Long): AtomicSwapQuote {
        val basis = reference ?: repository.quote(REFERENCE_UNITS).also { reference = it }
        var units = unitsFor(totalZat, basis)
        repeat(MAX_QUOTE_ATTEMPTS) {
            val quote = repository.quote(units)
            reference = quote
            if (quote.totalZat() <= totalZat) {
                return quote.copy(offer = quote.offer.copy(maxTotalZat = totalZat))
            }
            units = minOf(units - 1, unitsFor(totalZat, quote))
        }
        throw ZecInputQuoteException()
    }

    suspend fun maximum(availableZat: Long): Long =
        minOf(availableZat, repository.quote(deployment.maxUnits).also { reference = it }.totalZat())

    private fun unitsFor(totalZat: Long, quote: AtomicSwapQuote): Int {
        val deposit = totalZat - (quote.depositFeeZat ?: FEE_FALLBACK_ZAT)
        if (deposit <= 0) throw ZecInputQuoteException()
        val units =
            deposit.toBigInteger() * quote.offer.units.toBigInteger() /
                quote.offer.quote.depositZat
                    .toBigInteger()
        if (units < deployment.minUnits.toBigInteger() || units > deployment.maxUnits.toBigInteger()) {
            throw ZecInputQuoteException()
        }
        return units.toInt()
    }

    private fun AtomicSwapQuote.totalZat() = offer.quote.depositZat + (depositFeeZat ?: FEE_FALLBACK_ZAT)

    companion object {
        const val ZEC_DECIMALS = 8
        private const val REFERENCE_UNITS = 1_000_000
        private const val MAX_QUOTE_ATTEMPTS = 3
        private const val FEE_FALLBACK_ZAT = 15_000L
        private const val MAX_ZAT = 2_100_000_000_000_000L

        fun zatoshi(amount: NumberTextFieldInnerState): Long? =
            try {
                amount.amount
                    ?.movePointRight(ZEC_DECIMALS)
                    ?.longValueExact()
                    ?.takeIf { it in 1..MAX_ZAT }
            } catch (_: ArithmeticException) {
                null
            }

        fun input(zatoshi: Long) = NumberTextFieldInnerState.fromAmount(BigDecimal.valueOf(zatoshi, ZEC_DECIMALS))
    }
}

internal class ZecInputQuoteException : Exception("no quote fits the entered ZEC amount")
