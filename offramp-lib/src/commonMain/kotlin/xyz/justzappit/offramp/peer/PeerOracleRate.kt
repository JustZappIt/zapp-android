// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.peer

import xyz.justzappit.evm.abi.AbiDecoder
import xyz.justzappit.evm.math.BigDecimal
import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.math.DecimalRounding
import xyz.justzappit.evm.math.bigDecimalFromBigInteger
import xyz.justzappit.evm.math.decimalDivide
import xyz.justzappit.evm.math.decimalMovePointLeft
import xyz.justzappit.evm.rpc.BaseRpcClient
import xyz.justzappit.evm.types.Address

/**
 * Reads a Chainlink feed for the indicative rate. Indicative is the whole point: the binding rate
 * is whatever the oracle says when a buyer signals, so this is recomputed on every screen entry and
 * never presented as locked.
 *
 * A stale or failed read hides the rate. It never falls back to a cached number shown as live.
 */
class PeerOracleRate(
    private val rpcClient: BaseRpcClient,
    private val maxStalenessSeconds: Long = PeerNetworks.ORACLE_MAX_STALENESS_SECONDS,
) {
    private val one = BigDecimal("1")

    /** The rate with its currency attached, so no caller can render it under a different label. */
    suspend fun quote(currency: PeerCurrency, nowSeconds: Long): PeerRateQuote? =
        fiatPerUsdc(currency, nowSeconds)?.let {
            PeerRateQuote(currency = currency, fiatPerUsdc = it, readAtSeconds = nowSeconds)
        }

    /** Fiat per 1 USDC, or null when the feed is unreadable or stale. USD short-circuits to 1. */
    suspend fun fiatPerUsdc(currency: PeerCurrency, nowSeconds: Long): BigDecimal? {
        val feed = currency.feed ?: return one
        val scaled =
            readFreshAnswer(feed, nowSeconds)
                ?.let { decimalMovePointLeft(bigDecimalFromBigInteger(it), PeerCurrency.FEED_DECIMALS) }
        return when {
            scaled == null -> null
            currency.invert -> decimalDivide(one, scaled, RATE_SCALE, DecimalRounding.HALF_UP)
            else -> scaled
        }
    }

    /** Null for anything that is not a positive, in-date price: a bad read is never quoted. */
    private suspend fun readFreshAnswer(feed: Address, nowSeconds: Long): BigInteger? {
        val decoder =
            runCatching { rpcClient.ethCall(feed, PeerEscrowCalls.latestRoundDataCalldata()) }
                .getOrNull()
                ?.let(::AbiDecoder)
                ?.takeIf { it.byteSize >= ROUND_WORDS * AbiDecoder.WORD }
                ?: return null
        val answer = decoder.uint(WORD_ANSWER)
        val updatedAt = decoder.uint(WORD_UPDATED_AT).toLong()
        // int256 read unsigned: a set top bit is a negative price, which is not a rate we can quote.
        return answer.takeIf {
            it.signum() > 0 &&
                it.bitLength() <= MAX_POSITIVE_INT256_BITS &&
                nowSeconds - updatedAt <= maxStalenessSeconds
        }
    }

    private companion object {
        const val ROUND_WORDS = 5
        const val WORD_ANSWER = 1
        const val WORD_UPDATED_AT = 3
        const val MAX_POSITIVE_INT256_BITS = 255
        const val RATE_SCALE = 12
    }
}

/** A rate that cannot be shown under the wrong label, because it carries its own currency. */
data class PeerRateQuote(
    val currency: PeerCurrency,
    val fiatPerUsdc: BigDecimal,
    val readAtSeconds: Long,
)
