// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.peer

import xyz.justzappit.evm.math.bigIntegerValueOf
import xyz.justzappit.offramp.p2p.Usdc6
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The band is the one number on the amount screen the user plans around, so what it is allowed to
 * be built from matters more than how it is computed.
 */
class PeerMarketBandTest {
    @Test
    fun `a deposit too young to have resolved says nothing`() {
        val young = PeerQueueSample(depositTimestampSeconds = NOW - HOUR, firstSignalTimestampSeconds = null)

        assertEquals(null, young.observedWaitSeconds(NOW, MATURITY))
    }

    /**
     * Dropping unfilled deposits surveys only the orders that filled, and inside a window of recent
     * deposits those are the fast ones. A mature unfilled deposit is the slow tail, and it enters at
     * the wait it has already made.
     */
    @Test
    fun `a mature deposit no buyer took enters at the wait it has already made`() {
        val stalled = PeerQueueSample(depositTimestampSeconds = NOW - DAY, firstSignalTimestampSeconds = null)

        assertEquals(DAY, stalled.observedWaitSeconds(NOW, MATURITY))
    }

    @Test
    fun `a filled deposit carries the wait a buyer actually made`() {
        val filled =
            PeerQueueSample(
                depositTimestampSeconds = NOW - DAY,
                firstSignalTimestampSeconds = NOW - DAY + HOUR,
            )

        assertEquals(HOUR, filled.observedWaitSeconds(NOW, MATURITY))
    }

    @Test
    fun `the slow tail is what keeps the band honest`() {
        val fast = List(FILL_FLOOR) { sampleFilledAfter(MINUTE) }
        val stalled = List(FILL_FLOOR) { sampleUnfilled() }

        val fastOnly = bandOf(fast)
        val withTail = bandOf(fast + stalled)

        assertTrue(fastOnly is PeerMarketVerdict.Band)
        assertTrue(withTail is PeerMarketVerdict.Band)
        assertTrue(withTail.highSeconds > fastOnly.highSeconds, "stalled orders must widen the band")
    }

    @Test
    fun `too few fills is unknown rather than a guess`() {
        val verdict = bandOf(List(FILL_FLOOR) { sampleFilledAfter(MINUTE) }, fills = FILL_FLOOR - 1)

        assertEquals(PeerMarketVerdict.Unknown, verdict)
    }

    @Test
    fun `a pair nobody has bought in days reports little activity, not a stale median`() {
        val verdict =
            PeerMarket
                .summarise(
                    platform = PeerPlatform.REVOLUT,
                    currency = PeerCurrency.EUR,
                    queueSamples = List(FILL_FLOOR) { sampleFilledAfter(MINUTE) },
                    fillSamples = listOf(fill(atSeconds = NOW - 4 * DAY)),
                    nowSeconds = NOW,
                ).verdict

        assertEquals(PeerMarketVerdict.LittleActivity, verdict)
    }

    /** A median past the credible ceiling is not an estimate anyone can act on. */
    @Test
    fun `an implausible median is withheld`() {
        val verdict = bandOf(List(FILL_FLOOR) { sampleFilledAfter(3 * DAY) })

        assertEquals(PeerMarketVerdict.Unknown, verdict)
    }

    @Test
    fun `only fills on the selected currency count`() {
        val snapshot =
            PeerMarket.summarise(
                platform = PeerPlatform.REVOLUT,
                currency = PeerCurrency.EUR,
                queueSamples = emptyList(),
                fillSamples = listOf(fill(currency = PeerCurrency.GBP), fill(currency = PeerCurrency.EUR)),
                nowSeconds = NOW,
            )

        assertEquals(1, snapshot.fillsInWindow)
    }

    @Test
    fun `an order far above the average fill is flagged, one merely larger is not`() {
        val snapshot =
            PeerMarket.summarise(
                platform = PeerPlatform.REVOLUT,
                currency = PeerCurrency.EUR,
                queueSamples = emptyList(),
                fillSamples = listOf(fill(amount = usdc(100))),
                nowSeconds = NOW,
            )

        assertTrue(snapshot.isOversized(usdc(400)))
        assertTrue(!snapshot.isOversized(usdc(200)))
    }

    private fun bandOf(samples: List<PeerQueueSample>, fills: Int = FILL_FLOOR): PeerMarketVerdict =
        PeerMarket
            .summarise(
                platform = PeerPlatform.REVOLUT,
                currency = PeerCurrency.EUR,
                queueSamples = samples,
                fillSamples = List(fills) { fill() },
                nowSeconds = NOW,
            ).verdict

    private fun sampleFilledAfter(waitSeconds: Long) =
        PeerQueueSample(
            depositTimestampSeconds = NOW - DAY,
            firstSignalTimestampSeconds = NOW - DAY + waitSeconds,
        )

    private fun sampleUnfilled() =
        PeerQueueSample(depositTimestampSeconds = NOW - DAY, firstSignalTimestampSeconds = null)

    private fun fill(
        currency: PeerCurrency = PeerCurrency.EUR,
        amount: Usdc6 = usdc(50),
        atSeconds: Long = NOW - HOUR,
    ) = PeerFillSample(currency = currency, amount = amount, signalTimestampSeconds = atSeconds)

    private fun usdc(whole: Long) = Usdc6.ofMicros(bigIntegerValueOf(whole * 1_000_000L))

    private companion object {
        const val MINUTE = 60L
        const val HOUR = 60L * 60
        const val DAY = 24L * HOUR
        const val NOW = 1_800_000_000L
        val MATURITY = PeerMarket.MATURITY_SECONDS
        val FILL_FLOOR = PeerMarket.MIN_FILLS_FOR_BAND
    }
}
