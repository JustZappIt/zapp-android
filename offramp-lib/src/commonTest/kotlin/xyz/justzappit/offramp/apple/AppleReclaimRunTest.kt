// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.apple

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import xyz.justzappit.offramp.p2p.CurrencyCode
import xyz.justzappit.offramp.reputation.SocialPlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The guard that keeps one verification live at a time, walked through each of its exits. */
class AppleReclaimRunTest {
    @Test
    fun `a second run while one is live is refused rather than started`() =
        runTest {
            val lock = Mutex()
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val live =
                async {
                    singleRunFlow(lock, LINKEDIN, INR) { _, _ ->
                        emit(AppleReclaimStatus.Preparing)
                        started.complete(Unit)
                        release.await()
                    }.toList()
                }
            started.await()

            val second = singleRunFlow(lock, LINKEDIN, INR) { _, _ -> emit(AppleReclaimStatus.Preparing) }.toList()
            assertEquals(listOf(AppleReclaimStatus.Failed(AppleReputationClient.BUSY_REASON)), second)

            release.complete(Unit)
            assertEquals(listOf(AppleReclaimStatus.Preparing), live.await())
        }

    @Test
    fun `abandoning a run frees the next one`() =
        runTest {
            val lock = Mutex()
            val started = CompletableDeferred<Unit>()
            val abandoned =
                launch {
                    singleRunFlow(lock, LINKEDIN, INR) { _, _ ->
                        emit(AppleReclaimStatus.Preparing)
                        started.complete(Unit)
                        CompletableDeferred<Unit>().await()
                    }.toList()
                }
            started.await()
            abandoned.cancelAndJoin()

            val next = singleRunFlow(lock, LINKEDIN, INR) { _, _ -> emit(AppleReclaimStatus.Verifying) }.toList()
            assertEquals(listOf(AppleReclaimStatus.Verifying), next)
        }

    @Test
    fun `a platform id this build does not know never reaches the driver`() =
        runTest {
            var ran = false
            val statuses = singleRunFlow(Mutex(), "Myspace", INR) { _, _ -> ran = true }.toList()
            assertEquals(listOf(AppleReclaimStatus.Failed(AppleReputationClient.UNKNOWN_REASON)), statuses)
            assertTrue(!ran)
        }

    @Test
    fun `an unknown currency never reaches the driver`() =
        runTest {
            var ran = false
            val statuses = singleRunFlow(Mutex(), LINKEDIN, "XYZ") { _, _ -> ran = true }.toList()
            assertEquals(listOf(AppleReclaimStatus.Failed(AppleReputationClient.UNKNOWN_REASON)), statuses)
            assertTrue(!ran)
        }

    @Test
    fun `a refused run leaves the lock free for the run that is allowed`() =
        runTest {
            val lock = Mutex()
            singleRunFlow(lock, "Myspace", INR) { _, _ -> }.toList()
            val allowed = singleRunFlow(lock, LINKEDIN, INR) { _, _ -> emit(AppleReclaimStatus.Preparing) }
            assertEquals(AppleReclaimStatus.Preparing, allowed.first())
        }

    @Test
    fun `the routing keys resolve to the platform and corridor the driver is given`() =
        runTest {
            var seen: Pair<SocialPlatform, CurrencyCode>? = null
            singleRunFlow(Mutex(), LINKEDIN, INR) { platform, currency -> seen = platform to currency }.toList()
            assertEquals(SocialPlatform.LinkedIn to CurrencyCode.Inr, seen)
        }

    private companion object {
        val LINKEDIN = SocialPlatform.LinkedIn.name
        val INR = CurrencyCode.Inr.code
    }
}
