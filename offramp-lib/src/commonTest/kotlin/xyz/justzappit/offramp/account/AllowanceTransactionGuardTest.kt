// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.account

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals

class AllowanceTransactionGuardTest {
    /** The second rail may not replace the allowance until the first consuming receipt exists. */
    @Test
    fun `Peer and Scan and Pay approval spend pairs cannot interleave`() =
        runTest {
            val guard = AllowanceTransactionGuard()
            val events = mutableListOf<String>()
            val peerCreateSent = CompletableDeferred<Unit>()
            val peerReceipt = CompletableDeferred<Unit>()

            val peer =
                async {
                    guard.withApprovalAndSpend {
                        events += "peer approve"
                        events += "peer create"
                        peerCreateSent.complete(Unit)
                        peerReceipt.await()
                        events += "peer create receipt"
                    }
                }
            peerCreateSent.await()

            val scanAndPay =
                async {
                    guard.withApprovalAndSpend {
                        events += "scan approve"
                        events += "scan place"
                        events += "scan place receipt"
                    }
                }
            yield()

            assertEquals(listOf("peer approve", "peer create"), events)
            peerReceipt.complete(Unit)
            awaitAll(peer, scanAndPay)
            assertEquals(
                listOf(
                    "peer approve",
                    "peer create",
                    "peer create receipt",
                    "scan approve",
                    "scan place",
                    "scan place receipt",
                ),
                events,
            )
        }
}
