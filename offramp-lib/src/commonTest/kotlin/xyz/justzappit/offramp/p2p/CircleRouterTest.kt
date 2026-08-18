// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.p2p

import kotlinx.coroutines.test.runTest
import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.math.bigIntegerOne
import xyz.justzappit.evm.math.bigIntegerValueOf
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CircleRouterTest {
    private val inrCurrency = "0x494e520000000000000000000000000000000000000000000000000000000000"

    private fun circle(
        id: String,
        score: Double,
        status: String,
        active: Int = 1,
        currency: String = inrCurrency,
    ) = CircleForRouting(
        circleId = id,
        currency = currency,
        metrics =
            CircleMetrics(
                circleScore = score.toString(),
                circleStatus = status,
                scoreState = CircleScoreState(activeMerchantsCount = active.toString()),
            ),
    )

    @Test
    fun `circleWeight applies recovery scale for paused`() {
        val r = CircleRouter()
        assertEquals(3.0, r.circleWeight(circle("1", 10.0, "paused")), 0.0001)
    }

    @Test
    fun `circleWeight caps bootstrap by BOOTSTRAP_MAX_WEIGHT`() {
        val r = CircleRouter()
        assertEquals(25.0, r.circleWeight(circle("1", 100.0, "bootstrap")), 0.0001)
        assertEquals(5.0, r.circleWeight(circle("1", 5.0, "bootstrap")), 0.0001)
    }

    @Test
    fun `active circle weight is the raw score`() {
        val r = CircleRouter()
        assertEquals(42.0, r.circleWeight(circle("1", 42.0, "active")), 0.0001)
    }

    @Test
    fun `filterEligible drops other currencies`() {
        val r = CircleRouter()
        val list =
            listOf(
                circle("1", 10.0, "active", currency = inrCurrency),
                circle("2", 10.0, "active", currency = "0xdeadbeef"),
            )
        val out = r.filterEligible(list, inrCurrency)
        assertEquals(1, out.size)
        assertEquals("1", out[0].circleId)
    }

    @Test
    fun `selectCircle on empty returns null`() {
        assertNull(CircleRouter().selectCircle(emptyList()))
    }

    @Test
    fun `selectCircleForOrder picks the heavily-weighted circle deterministically`() =
        runTest {
            // epsilon=0 means always exploit (active-only by score). With weights [100, 1] and a
            // fixed RNG seed, the result is fully deterministic — assert the specific circle id so
            // a regression in the weighted-choice path (e.g. wrong index direction) actually fails.
            val router = CircleRouter(random = Random(0), epsilon = 0.0)
            val circles =
                listOf(
                    circle("1", 100.0, "active"),
                    circle("2", 1.0, "active"),
                )
            val chosen = router.selectCircleForOrder(circles, inrCurrency, validateCircle = { true })
            assertEquals(bigIntegerOne, chosen.value)
        }

    @Test
    fun `selectCircleForOrder retries when validation fails and picks the other circle`() =
        runTest {
            // With only 2 circles in the pool, rejecting the first pick forces the second to be
            // the only remaining id — so retry behaviour is asserted via the specific second id,
            // not "either of {1, 2}". Track which id was rejected to derive the expected survivor.
            val router = CircleRouter(random = Random(42), epsilon = 0.0)
            val circles =
                listOf(
                    circle("1", 100.0, "active"),
                    circle("2", 1.0, "active"),
                )
            var calls = 0
            var rejectedId: BigInteger? = null
            val firstId =
                router.selectCircleForOrder(circles, inrCurrency) { id ->
                    calls++
                    if (calls == 1) {
                        rejectedId = id.value
                        false
                    } else {
                        true
                    }
                }
            assertEquals(2, calls)
            val expectedSurvivor =
                if (rejectedId == bigIntegerOne) bigIntegerValueOf(2) else bigIntegerOne
            assertEquals(expectedSurvivor, firstId.value)
        }

    @Test
    fun `selectCircleForOrder fails after exhausting attempts`() =
        runTest {
            val router = CircleRouter(random = Random(7), epsilon = 0.0, maxValidationAttempts = 2)
            val circles = listOf(circle("1", 100.0, "active"), circle("2", 1.0, "active"))
            assertFailsWith<IllegalStateException> {
                router.selectCircleForOrder(circles, inrCurrency) { false }
            }
        }

    @Test
    fun `selectCircleForOrder fails when no eligible currency match`() =
        runTest {
            val router = CircleRouter()
            val circles = listOf(circle("1", 10.0, "active", currency = "0xabcd"))
            assertFailsWith<IllegalStateException> {
                router.selectCircleForOrder(circles, inrCurrency) { true }
            }
        }

    @Test
    fun `selectCircleForOrder propagates exceptions from validateCircle instead of swallowing them`() =
        runTest {
            // Regression for: validateCircle used to be wrapped in
            // runCatching{...}.getOrElse{false}, so an RPC blip in the orchestrator's on-chain
            // merchant-availability check looked like "invalid circle". Over the default 3
            // attempts that swallowed three transport errors and surfaced as "Exhausted N
            // validation attempts" — masking the real cause. The exception now propagates.
            val router = CircleRouter(random = Random(0), epsilon = 0.0, maxValidationAttempts = 3)
            val circles =
                listOf(
                    circle("1", 100.0, "active"),
                    circle("2", 1.0, "active"),
                )
            val rpcFailure = RuntimeException("simulated RPC timeout")
            val thrown =
                assertFailsWith<RuntimeException> {
                    router.selectCircleForOrder(circles, inrCurrency) { throw rpcFailure }
                }
            assertEquals(rpcFailure, thrown)
        }

    @Test
    fun `epsilon one explores across all statuses and not only active`() =
        runTest {
            // Weights after status scaling: paused 50.0 * 0.3 = 15.0; bootstrap min(5, 25) = 5.0.
            // Ratio 15:5 → ~75% chance circle 1 wins on a single draw; with seed 1 the weighted
            // random happens to land on circle 2 (a perfectly normal outcome of the 25% tail).
            // The real assertion: no-active-circles path doesn't throw AND lands on a specific
            // id, not just "anything goes".
            val router = CircleRouter(random = Random(1), epsilon = 1.0)
            val circles =
                listOf(
                    circle("1", 50.0, "paused"),
                    circle("2", 5.0, "bootstrap"),
                )
            val chosen = router.selectCircleForOrder(circles, inrCurrency) { true }
            assertEquals(bigIntegerValueOf(2), chosen.value)
        }
}
