// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.atomicswap

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import xyz.justzappit.offramp.p2p.Usdc6
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AtomicSwapFeeApprovalTest : AtomicSwapDriverFixtures() {
    @Test
    fun legacyRecordNeedsExplicitFeeApprovalBeforeSigning() =
        runTest {
            val h = Harness()
            val record = h.minedDeposit().copy(relayerFee = null)
            h.store.save(record)
            h.chain.stage = SwapStage.CLAIMED
            val failure = assertFailsWith<AtomicSwapBlockedException> { h.driver.advance(record) }
            assertEquals(AtomicSwapBlock.RELAYER_FEE, failure.reason)
            assertTrue(h.keys.signedFees.isEmpty())
            assertTrue("/v1/payout" !in h.paths)
            val fee = h.driver.payoutFees.quote(record.index)
            assertTrue(h.keys.signedFees.isEmpty())
            h.driver.payoutFees.approve(record, fee)
            h.driver.advance(assertNotNull(h.store.record))
            assertEquals(listOf(fee), h.keys.signedFees)
        }

    @Test
    fun changedFeeOrRecordCannotUseAnEarlierApproval() =
        runTest {
            val h = Harness()
            val record = h.minedDeposit().copy(relayerFee = null)
            h.store.save(record)
            val fee = h.driver.payoutFees.quote(record.index)
            h.relayerFee = "30000"
            assertFailsWith<IllegalStateException> { h.driver.payoutFees.approve(record, fee) }
            assertEquals(null, h.store.record?.relayerFee)
            assertFailsWith<IllegalStateException> {
                h.driver.payoutFees.approve(record.copy(swapId = SwapId.of(ByteArray(32))), Usdc6.ofMicros(30000))
            }
            assertEquals(null, h.store.record?.relayerFee)
        }

    @Test
    fun savedAuthorizationSurvivesSerializationAndMissingLegacyFeeCap() =
        runTest {
            val h = Harness()
            val record = h.minedDeposit()
            h.chain.stage = SwapStage.READY
            h.chain.railgunAccepts = false
            assertFailsWith<AtomicSwapBlockedException> { h.driver.advance(record) }
            val saved = assertNotNull(h.store.record)
            val payout = assertNotNull(saved.payout)
            h.store.save(Json.decodeFromString(Json.encodeToString(saved.copy(relayerFee = null))))
            h.relayerFee = "90000"
            h.chain.railgunAccepts = true
            h.chain.stage = SwapStage.CLAIMED
            h.driver.advance(assertNotNull(h.store.record))
            assertEquals(listOf(record.relayerFee), h.keys.signedFees)
            assertEquals(payout, h.store.record?.payout)
            assertTrue(h.bodies.getValue("/v1/payout").contains("\"fee\":\"20000\""))
        }
}
