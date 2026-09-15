// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.apple

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import xyz.justzappit.offramp.liveness.LivenessFailure
import xyz.justzappit.offramp.liveness.LivenessReturn
import xyz.justzappit.offramp.liveness.LivenessStanding
import xyz.justzappit.offramp.liveness.LivenessStatus
import xyz.justzappit.offramp.p2p.CurrencyCode
import xyz.justzappit.offramp.p2p.Usdc6
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Swift boundary is only as good as the strings crossing it: a failure reason is a sentence
 * Swift picks by name, and the standing is what the row and the reputation screen render.
 */
class AppleLivenessMappingTest {
    @Test
    fun `every failure crosses as its own name, and the two facade reasons collide with none`() {
        LivenessFailure.entries.forEach { failure ->
            val mapped = LivenessStatus.Failed(failure).toApple() as AppleLivenessStatus.Failed
            assertEquals(failure.name, mapped.reason)
        }
        val names = LivenessFailure.entries.map { it.name }.toSet()
        assertEquals(LivenessFailure.entries.size, names.size)
        assertFalse(AppleLivenessClient.BUSY_REASON in names)
        assertFalse(AppleLivenessClient.UNKNOWN_REASON in names)
    }

    @Test
    fun `ready carries the widget url and how long it stays open`() {
        val ready =
            LivenessStatus
                .Ready(widgetUrl = "https://liveness.invalid/w/abc", expiresInSeconds = 600)
                .toApple() as AppleLivenessStatus.Ready
        assertEquals("https://liveness.invalid/w/abc", ready.widgetUrl)
        assertEquals(600, ready.expiresInSeconds)
    }

    @Test
    fun `the standing crosses as micro strings, never as rendered amounts`() {
        val standing = LivenessStanding(isVerified = true, limit = TWENTY_USD, tierCap = TWENTY_USD)
        val apple = standing.toApple()
        assertTrue(apple.isVerified)
        assertEquals("20000000", apple.limitMicros)
        assertEquals("20000000", apple.tierCapMicros)

        val cold = LivenessStanding(isVerified = false, limit = Usdc6.ZERO, tierCap = TWENTY_USD).toApple()
        assertFalse(cold.isVerified)
        assertEquals("0", cold.limitMicros)
        assertEquals(
            cold,
            (
                LivenessStatus
                    .Done(
                        LivenessStanding(false, Usdc6.ZERO, TWENTY_USD),
                    ).toApple() as AppleLivenessStatus.Done
            ).standing
        )
    }

    @Test
    fun `the stages cross as the objects Swift switches on`() {
        assertEquals(AppleLivenessStatus.Preparing, LivenessStatus.Preparing.toApple())
        assertEquals(AppleLivenessStatus.Verifying, LivenessStatus.Verifying.toApple())
        assertEquals(AppleLivenessStatus.Submitting, LivenessStatus.Submitting.toApple())
    }

    @Test
    fun `the corridor rides in state and comes back out of it`() {
        // A cold-started process has nothing but the return link to rebuild the screen from.
        val state = LivenessReturn.state("0f1e2d3c", CurrencyCode.Inr)
        assertEquals("0f1e2d3c.INR", state)
        assertEquals(CurrencyCode.Inr, LivenessReturn.currencyFromState(state))
        assertNull(LivenessReturn.currencyFromState("0f1e2d3c"))
        assertNull(LivenessReturn(code = "c", error = null, state = null).currency)
    }

    @Test
    fun `a second run while one is live is refused rather than started`() =
        runTest {
            val lock = Mutex()
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val live =
                async {
                    singleLivenessRun(lock) {
                        emit(AppleLivenessStatus.Preparing)
                        started.complete(Unit)
                        release.await()
                    }.toList()
                }
            started.await()

            val second = singleLivenessRun(lock) { emit(AppleLivenessStatus.Preparing) }.toList()
            assertEquals(listOf(AppleLivenessStatus.Failed(AppleLivenessClient.BUSY_REASON)), second)

            release.complete(Unit)
            assertEquals(listOf(AppleLivenessStatus.Preparing), live.await())
        }

    @Test
    fun `abandoning a run frees the next one`() =
        runTest {
            val lock = Mutex()
            val started = CompletableDeferred<Unit>()
            val abandoned =
                launch {
                    singleLivenessRun(lock) {
                        emit(AppleLivenessStatus.Preparing)
                        started.complete(Unit)
                        CompletableDeferred<Unit>().await()
                    }.toList()
                }
            started.await()
            abandoned.cancelAndJoin()

            val next = singleLivenessRun(lock) { emit(AppleLivenessStatus.Verifying) }.toList()
            assertEquals(listOf(AppleLivenessStatus.Verifying), next)
        }

    private companion object {
        val TWENTY_USD = Usdc6.ofMicros(20_000_000L)
    }
}
