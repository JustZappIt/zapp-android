// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.peer

import xyz.justzappit.evm.math.bigIntegerValueOf
import xyz.justzappit.evm.math.div
import xyz.justzappit.evm.math.times
import xyz.justzappit.offramp.p2p.Usdc6

/**
 * Zapp does not set the rate, so the honest thing to be precise about is whether and when an order
 * fills, and in how many pieces. All of it is measurable from the free indexer before the user
 * commits a single satoshi.
 */
data class PeerQueueSample(
    val depositTimestampSeconds: Long,
    val firstSignalTimestampSeconds: Long?,
) {
    /** The wait a buyer actually made. Null while none has. */
    val waitSeconds: Long?
        get() = firstSignalTimestampSeconds?.minus(depositTimestampSeconds)?.takeIf { it >= 0 }

    /**
     * What this deposit contributes to the distribution, or null if it is too young to say.
     *
     * A deposit no buyer has taken is right-censored, and both obvious ways of handling that quote
     * a band nobody waited. Dropping it keeps only orders that filled, which is a survey of the
     * fast ones. Counting a minutes-old one as a minutes-long wait is worse still. So the sample is
     * restricted to deposits old enough to have resolved either way: inside it a filled deposit
     * carries its real wait and an unfilled one carries its age, which is a floor on the wait it is
     * still making.
     */
    fun observedWaitSeconds(nowSeconds: Long, maturitySeconds: Long): Long? {
        val age = nowSeconds - depositTimestampSeconds
        return when {
            age < maturitySeconds -> null
            else -> waitSeconds ?: age
        }
    }
}

data class PeerFillSample(
    val currency: PeerCurrency?,
    val amount: Usdc6,
    val signalTimestampSeconds: Long,
)

/** What the amount screen renders. Fails open to [PeerMarketVerdict.Unknown] when reads fail. */
sealed interface PeerMarketVerdict {
    /** Show a range, never a point estimate: the rate and the wait are both distributions. */
    data class Band(
        val lowSeconds: Long,
        val highSeconds: Long,
    ) : PeerMarketVerdict

    /** The pair has data, and the data says nobody is buying it right now. */
    data object LittleActivity : PeerMarketVerdict

    /** No usable reading. The UI falls back to generic copy rather than inventing one. */
    data object Unknown : PeerMarketVerdict
}

data class PeerMarketSnapshot(
    val platform: PeerPlatform,
    val currency: PeerCurrency,
    val fillsInWindow: Int,
    val averageFill: Usdc6?,
    val lastFillSecondsAgo: Long?,
    val verdict: PeerMarketVerdict,
) {
    /**
     * A buyer takes any slice inside the intent range, so a large order is many fills over hours,
     * not one. Warn past roughly three average fills rather than blocking: it is still valid.
     */
    fun isOversized(amount: Usdc6): Boolean {
        val average = averageFill?.takeIf { it > Usdc6.ZERO } ?: return false
        return amount.micros > average.micros * bigIntegerValueOf(OVERSIZE_MULTIPLE)
    }

    companion object {
        const val OVERSIZE_MULTIPLE: Long = 3L
    }
}

object PeerMarket {
    /** Rolling window for the demand reading. */
    const val WINDOW_SECONDS: Long = 30L * 24 * 60 * 60

    /** Below this many fills the median is noise, so the UI says nothing rather than guessing. */
    const val MIN_FILLS_FOR_BAND: Int = 10

    /** A median past this is not an estimate a user can act on. */
    const val MAX_CREDIBLE_MEDIAN_SECONDS: Long = 48L * 60 * 60

    /** No fill in this long reads as "little recent activity" rather than a stale median. */
    const val QUIET_PAIR_SECONDS: Long = 72L * 60 * 60

    /** Snapshots are cached this long: the numbers move slowly and the screen is re-entered often. */
    const val CACHE_TTL_SECONDS: Long = 15L * 60

    /**
     * How long a deposit has to have existed before it can speak to the wait. The indexer query
     * applies it too, so the row budget is spent on deposits that can answer.
     */
    const val MATURITY_SECONDS: Long = 6L * 60 * 60

    fun summarise(
        platform: PeerPlatform,
        currency: PeerCurrency,
        queueSamples: List<PeerQueueSample>,
        fillSamples: List<PeerFillSample>,
        nowSeconds: Long,
    ): PeerMarketSnapshot {
        val pairFills = fillSamples.filter { it.currency == currency }
        val lastFillSecondsAgo =
            pairFills.maxOfOrNull { it.signalTimestampSeconds }?.let { (nowSeconds - it).coerceAtLeast(0) }
        return PeerMarketSnapshot(
            platform = platform,
            currency = currency,
            fillsInWindow = pairFills.size,
            averageFill = averageOf(pairFills),
            lastFillSecondsAgo = lastFillSecondsAgo,
            verdict =
                verdictFor(
                    waits =
                        queueSamples
                            .mapNotNull { it.observedWaitSeconds(nowSeconds, MATURITY_SECONDS) }
                            .sorted(),
                    fillCount = pairFills.size,
                    lastFillSecondsAgo = lastFillSecondsAgo,
                ),
        )
    }

    private fun verdictFor(
        waits: List<Long>,
        fillCount: Int,
        lastFillSecondsAgo: Long?,
    ): PeerMarketVerdict {
        val median = percentile(waits, MEDIAN)
        val p75 = percentile(waits, P75)
        return when {
            lastFillSecondsAgo != null && lastFillSecondsAgo > QUIET_PAIR_SECONDS -> {
                PeerMarketVerdict.LittleActivity
            }

            fillCount < MIN_FILLS_FOR_BAND || median == null || p75 == null -> {
                PeerMarketVerdict.Unknown
            }

            median > MAX_CREDIBLE_MEDIAN_SECONDS -> {
                PeerMarketVerdict.Unknown
            }

            else -> {
                PeerMarketVerdict.Band(lowSeconds = median, highSeconds = maxOf(median, p75))
            }
        }
    }

    private fun averageOf(samples: List<PeerFillSample>): Usdc6? {
        if (samples.isEmpty()) return null
        val total = samples.fold(Usdc6.ZERO) { acc, sample -> acc + sample.amount }
        return Usdc6(total.micros / bigIntegerValueOf(samples.size.toLong()))
    }

    /** Nearest-rank on an already-sorted list. */
    internal fun percentile(sorted: List<Long>, fraction: Double): Long? {
        if (sorted.isEmpty()) return null
        val rank = (fraction * sorted.size).toInt().coerceIn(0, sorted.size - 1)
        return sorted[rank]
    }

    private const val MEDIAN = 0.5
    private const val P75 = 0.75
}
