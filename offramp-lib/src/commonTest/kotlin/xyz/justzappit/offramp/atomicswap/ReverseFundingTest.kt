// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.atomicswap

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import xyz.justzappit.evm.types.ChainId
import xyz.justzappit.evm.types.TxHash
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReverseFundingTest : ReverseSwapDriverFixtures() {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aLateFundingResponseKeepsTheUsersCancellation() =
        runTest {
            val h = Harness()
            h.prepared()
            h.submitting = CompletableDeferred()
            val sending = async { h.driver.fund(0) }
            runCurrent()
            h.driver.cancel(0)
            assertTrue(h.record.cancelRequested)
            val hash = TxHash.fromHex(hex(32, 8))
            h.submitting?.complete(hash)
            sending.await()
            assertTrue(h.record.cancelRequested)
            assertEquals(ReversePhase.REFUND_WAIT, h.record.phase)
            assertEquals(hash, h.record.funding?.txId)
            assertEquals(0, h.readySignatures)
        }

    @Test
    fun sponsoredFundingRetainsProofAcrossTimeoutAndRestartAndSavesTheReturnedHash() =
        runTest {
            val h = Harness()
            h.prepared()
            h.interruptFunding = true
            assertFailsWith<IllegalStateException> { h.driver.fund(0) }
            val kept = assertNotNull(h.record.funding)
            assertNull(kept.txId)

            h.interruptFunding = false
            h.submittedFundingHash = TxHash.fromHex(hex(32, 8))
            h.restart()
            h.driver.advance()

            assertEquals(1, h.prepares)
            assertEquals(listOf(kept, kept), h.submissions)
            assertEquals(kept.copy(txId = h.submittedFundingHash), h.record.funding)
            assertEquals(ReversePhase.CONFIRMING_ESCROW, h.record.phase)
        }

    @Test
    fun timeoutAfterFundingLandsReconcilesWithoutSubmittingOrProvingAgain() =
        runTest {
            val h = Harness()
            h.prepared()
            h.interruptFunding = true
            assertFailsWith<IllegalStateException> { h.driver.fund(0) }
            h.funded()
            h.restart()
            h.driver.advance()
            assertEquals(1, h.prepares)
            assertEquals(1, h.submissions.size)
            assertEquals(ReversePhase.RECEIVING_ZEC, h.record.phase)
        }

    @Test
    fun anEmptyFundingResponseDoesNotConfirmEscrowOrAuthorizeReady() =
        runTest {
            val h = Harness()
            h.submittedFundingHash = null
            h.prepared()
            h.driver.fund(0)
            assertNull(h.record.funding?.txId)
            assertEquals(ReversePhase.CONFIRMING_ESCROW, h.record.phase)
            assertFailsWith<IllegalStateException> { h.driver.ready(0) }
            assertEquals(0, h.readySignatures)
            h.funded()
            h.driver.advance()
            assertEquals(ReversePhase.RECEIVING_ZEC, h.record.phase)
        }

    @Test
    fun fundingIsNeverResubmittedAfterItsDeadline() =
        runTest {
            val h = Harness()
            h.submittedFundingHash = null
            h.prepared()
            h.driver.fund(0)
            h.now = FUNDING
            h.restart()
            h.driver.advance()
            assertEquals(1, h.submissions.size)
            h.now = FUNDING + FUNDING_SETTLED_AFTER_SECONDS + 1
            h.driver.advance()
            assertEquals(ReversePhase.CANCELLED, h.record.phase)
        }

    @Test
    fun sponsorshipMustServeThePinnedChainContractRelayerMakerTokenAndAdapter() {
        val funding = ReverseFundingTerms(CONTRACT, TOKEN, MAKER, 4_000_000, "20000000000", 65_536, AMOUNT)
        val terms = RelayerTerms(RELAYER, DEPLOYMENT.chainId, CONTRACT, AMOUNT, funding)
        assertEquals(funding, terms.requireReverseFunding(DEPLOYMENT, CONTRACT))
        for (bad in listOf(
            terms.copy(chainId = ChainId(1)),
            terms.copy(contract = USER),
            terms.copy(relayer = USER),
            terms.copy(reverseFunding = funding.copy(relayAdapt = USER)),
            terms.copy(reverseFunding = funding.copy(token = USER)),
            terms.copy(reverseFunding = funding.copy(maker = USER)),
        )) {
            val error = assertFailsWith<AtomicSwapBlockedException> { bad.requireReverseFunding(DEPLOYMENT, CONTRACT) }
            assertEquals(AtomicSwapBlock.WRONG_DEPLOYMENT, error.reason)
        }
        val unsupported =
            assertFailsWith<AtomicSwapBlockedException> {
                terms.copy(reverseFunding = null).requireReverseFunding(DEPLOYMENT, CONTRACT)
            }
        assertEquals(AtomicSwapBlock.FUNDING_UNAVAILABLE, unsupported.reason)
        val unpaid =
            assertFailsWith<AtomicSwapBlockedException> {
                terms.copy(reverseFunding = funding.copy(fee = null)).requireReverseFunding(DEPLOYMENT, CONTRACT)
            }
        assertEquals(AtomicSwapBlock.FUNDING_UNAVAILABLE, unpaid.reason)
    }

    @Test
    fun savedSponsoredFundingSurvivesStrictSerializationBeforeAndAfterSubmission() {
        val request =
            ReverseFundingRequest(SwapId.of(ByteArray(32) { 1 }), DEPLOYMENT.chainId, CONTRACT, "0x12345678", "0")
        val pending = ReverseFundingTransaction(cost = ReverseFundingCost(AMOUNT, AMOUNT, AMOUNT), request = request)
        for (funding in listOf(pending, pending.copy(txId = TxHash.fromHex(hex(32, 8))))) {
            assertEquals(funding, Json.decodeFromString<ReverseFundingTransaction>(Json.encodeToString(funding)))
        }
        assertFailsWith<IllegalArgumentException> { request.copy(data = "0xzzzzzzzz") }
        assertFailsWith<IllegalArgumentException> { request.copy(value = "1") }
    }
}
