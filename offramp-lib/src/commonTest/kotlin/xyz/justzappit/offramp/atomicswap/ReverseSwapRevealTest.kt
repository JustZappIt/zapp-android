// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.atomicswap

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ReverseSwapRevealTest : ReverseSwapDriverFixtures() {
    @Test
    fun aLockExpiringDuringPreparationNeverDisclosesTheSecret() =
        runTest {
            for (step in 0..2) {
                val h = Harness()
                h.prepared()
                h.funded()
                h.saved = h.record.copy(cancelRequested = true)
                h.refundUntil = h.now + 600
                val lapse = { h.now += 601 }
                when (step) {
                    0 -> h.beforePayout = lapse
                    1 -> h.beforeUpdate = lapse
                    else -> h.beforeSecret = lapse
                }
                h.driver.advance()
                assertEquals(0, h.refundCalls, "step $step")
            }
        }

    @Test
    fun settlementDuringSecretDerivationNeverDisclosesTheSecret() =
        runTest {
            val h = Harness()
            h.prepared()
            h.funded()
            h.saved = h.record.copy(cancelRequested = true)
            h.refundUntil = h.now + 600
            h.beforeSecret = { h.escrow = h.swap(SwapStage.CLAIMED) }
            h.driver.advance()
            assertEquals(0, h.refundCalls)
        }

    @Test
    fun rescueKeepsTheExactNonceAndDeadlineBeforeSending() =
        runTest {
            val h = Harness()
            h.prepared()
            h.escrow = h.swap(SwapStage.REFUNDED)
            h.paidOut = true
            h.driver.advance()
            h.rescueNonce = 7
            h.driver.rescue(0)
            val rescue = assertNotNull(h.record.rescue)
            assertEquals(7, rescue.nonce)
            assertEquals(h.now + SIGNATURE_TTL_SECONDS, rescue.deadline)
            h.restart()
            assertEquals(rescue, h.record.rescue)
        }
}
