// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.atomicswap

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import xyz.justzappit.evm.math.bigIntegerOne
import xyz.justzappit.evm.math.plus
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.offramp.p2p.Usdc6
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AtomicSwapDriverTest : AtomicSwapDriverFixtures() {
    @Test
    fun happyPathClaimsIntoRailgun() =
        runTest {
            val h = Harness()
            val record = h.accepted()

            assertEquals(confirming(), h.driver.advance(record))
            assertEquals(listOf("utest1deposit" to DEPOSIT_ZAT), h.zcash.deposits)
            assertEquals(txId("deposit1"), h.store.depositTxId)
            assertEquals(listOf(txId("deposit1")), h.zcash.submitted())

            h.chain.stage = SwapStage.READY
            val step = h.driver.advance(h.store.record!!)

            assertEquals(AtomicSwapStep.Finished(AtomicSwapOutcome.Paid), step)
            assertEquals(
                listOf(
                    "/v1/info",
                    "/v1/quote",
                    "/v1/terms",
                    "/v1/quote/$QUOTE_ID/accept",
                    "/v1/terms",
                    "/v1/lock-claim",
                    "/v1/claim",
                ),
                h.paths,
            )
            assertTrue(h.bodies.getValue("/v1/claim").contains("0x" + "09".repeat(32)), "the claim reveals z")
            val paid = checkNotNull(h.store.record)
            assertEquals(AtomicSwapOutcome.Paid, paid.outcome)
            assertEquals(h.clock, paid.end?.at)
            assertEquals(TxHash.fromHex(hash(1)), paid.payoutTx, "the claim's second transaction is the payout")
        }

    @Test
    fun anOfferSaysWhatArrivesAfterTheRelayerAndRailgunTakeTheirFees() =
        runTest {
            val offer = Harness().driver.quote(ONE_UNIT)

            assertEquals(Usdc6.ofMicros(20_000), offer.relayerFee)
            assertEquals(Usdc6.ofMicros(977_550), offer.receives)
            assertEquals(0, offer.index)
        }

    @Test
    fun eachQuoteTakesItsOwnIndex() =
        runTest {
            val h = Harness()
            h.driver.quote(ONE_UNIT)
            assertEquals(1, h.driver.quote(ONE_UNIT).index)
        }

    @Test
    fun anIndexTheChainShowsInUseIsNeverQuotedAgain() =
        runTest {
            val h = Harness()
            h.chain.opened = true

            val blocked = assertFailsWith<AtomicSwapBlockedException> { h.driver.quote(ONE_UNIT) }
            assertEquals(AtomicSwapBlock.INDICES_IN_USE, blocked.reason)
            assertTrue("/v1/quote" !in h.paths, "the maker never hears of a used index")
        }

    @Test
    fun aQuoteForAnotherDeploymentIsRefused() =
        runTest {
            val h = Harness()
            h.quoteJson = QUOTE_JSON.replace("11155111", "1")

            val refused = assertFailsWith<AtomicSwapBlockedException> { h.driver.quote(ONE_UNIT) }
            assertEquals(AtomicSwapBlock.WRONG_DEPLOYMENT, refused.reason)
        }

    @Test
    fun aQuoteFromAnotherMakerIsRefused() =
        runTest {
            val h = Harness()
            h.quoteJson = QUOTE_JSON.replace(MAKER.checksumHex, AUTH.checksumHex)

            val refused = assertFailsWith<AtomicSwapBlockedException> { h.driver.quote(ONE_UNIT) }
            assertEquals(AtomicSwapBlock.WRONG_DEPLOYMENT, refused.reason)
        }

    @Test
    fun aMalformedQuoteIsTheMakersUnreadableAnswer() =
        runTest {
            val h = Harness()
            h.quoteJson = QUOTE_JSON.replace("\"amount\":\"1000000\"", "\"amount\":\"1.5\"")

            val failed = assertFailsWith<AtomicSwapHttpException.Unreadable> { h.driver.quote(ONE_UNIT) }
            assertEquals(AtomicSwapService.MAKER, failed.service)
        }

    @Test
    fun aQuoteAboutToRunOutIsNotAccepted() =
        runTest {
            val h = Harness()
            val offer = h.driver.quote(ONE_UNIT)
            h.clock = NOW + 290

            val refused = assertFailsWith<AtomicSwapBlockedException> { h.driver.accept(offer) }
            assertEquals(AtomicSwapBlock.QUOTE_EXPIRED, refused.reason)
            assertTrue(h.paths.none { it.endsWith("/accept") })
            assertNull(h.store.record)
        }

    @Test
    fun anAcceptedSwapKeepsTheRelayerFeeItWasOffered() =
        runTest {
            val record = Harness().accepted()

            assertEquals(Usdc6.ofMicros(20_000), record.relayerFee)
        }

    @Test
    fun aNewSwapPaysTheRailgunWalletDerivedFromTheSeed() =
        runTest {
            val h = Harness()
            val record = h.accepted()

            assertEquals(RailgunKeySource.BIP85, record.railgunKeys)
            assertEquals(
                listOf(RailgunKeySource.BIP85, RailgunKeySource.BIP85),
                h.keys.railgunKeys,
                "the quote's note and the acceptance's proof",
            )
            assertTrue(h.bodies.getValue("/v1/quote").contains(NOTE_COMMITMENT.hex))
        }

    @Test
    fun aSwapAcceptedBeforeTheDerivedWalletIsPaidIntoTheZcashSeedsOwn() =
        runTest {
            val h = Harness()
            val legacy = notes.getValue(RailgunKeySource.ZCASH_SEED)
            val record = h.depositedRecord().copy(railgunKeys = RailgunKeySource.ZCASH_SEED)
            h.store.record = record
            h.chain.payoutNote = legacy.commitment
            h.chain.stage = SwapStage.CLAIMED

            assertEquals(AtomicSwapStep.Finished(AtomicSwapOutcome.Paid), h.driver.advance(record))
            assertTrue(h.bodies.getValue("/v1/payout").contains(legacy.npk.hex()))
            assertEquals(RailgunKeySource.ZCASH_SEED, h.keys.railgunKeys.last())
        }

    @Test
    fun aQuoteTheMakerNoLongerKnowsEndsWithNothingSent() =
        runTest {
            val h = Harness()
            h.acceptStatus = HttpStatusCode.NotFound

            val record = h.driver.accept(h.driver.quote(ONE_UNIT))

            assertEquals(AtomicSwapOutcome.NothingSent(NothingSentCause.QUOTE_EXPIRED), record.outcome)
            assertEquals(record, h.store.record)
        }

    @Test
    fun aMakerWithoutInventoryEndsWithNothingSent() =
        runTest {
            val h = Harness()
            h.acceptStatus = HttpStatusCode.ServiceUnavailable

            val record = h.driver.accept(h.driver.quote(ONE_UNIT))

            assertEquals(AtomicSwapOutcome.NothingSent(NothingSentCause.MAKER_UNAVAILABLE), record.outcome)
        }

    @Test
    fun anAcceptAnsweredWithAnotherSwapEndsWithNothingSent() =
        runTest {
            val h = Harness()
            h.acceptedSwapId = hash(7)

            val record = h.driver.accept(h.driver.quote(ONE_UNIT))

            assertEquals(AtomicSwapOutcome.NothingSent(NothingSentCause.MISMATCH), record.outcome)
        }

    @Test
    fun anAcceptThatMayHaveOpenedWaitsForTheSwapThenGivesUp() =
        runTest {
            val h = Harness()
            h.acceptStatus = HttpStatusCode.InternalServerError

            assertFailsWith<AtomicSwapHttpException> { h.driver.accept(h.driver.quote(ONE_UNIT)) }
            val record = h.store.record!!
            assertNull(record.outcome)
            assertEquals(AtomicSwapStep.Waiting(AtomicSwapWait.OPENING), h.driver.advance(record))

            h.clock = NOW + 300 + 301
            assertEquals(
                AtomicSwapStep.Finished(AtomicSwapOutcome.NothingSent(NothingSentCause.NEVER_OPENED)),
                h.driver.advance(record),
            )
        }

    @Test
    fun onlyOneSwapIsUnderWayAtATime() =
        runTest {
            val h = Harness()
            h.accepted()

            val refused = assertFailsWith<AtomicSwapBlockedException> { h.driver.quote(ONE_UNIT) }
            assertEquals(AtomicSwapBlock.SWAP_UNDER_WAY, refused.reason)
        }

    @Test
    fun anInterruptedDepositThatWentOutIsRecordedNotPaidAgain() =
        runTest {
            val h = Harness()
            val record = h.accepted().copy(deposit = SwapDeposit.Started)
            h.zcash.created = transaction("earlier")

            assertEquals(confirming(), h.driver.advance(record))
            assertTrue(h.zcash.deposits.isEmpty())
            assertEquals(txId("earlier"), h.store.depositTxId)
            assertEquals(listOf(txId("earlier")), h.zcash.submitted(), "a deposit found again is sent again")
        }

    @Test
    fun anInterruptedDepositThatNeverWentOutIsPaidOnce() =
        runTest {
            val h = Harness()
            val record = h.accepted().copy(deposit = SwapDeposit.Started)

            assertEquals(confirming(), h.driver.advance(record))
            assertEquals(txId("deposit1"), h.store.depositTxId)
            assertEquals(listOf("utest1deposit" to DEPOSIT_ZAT), h.zcash.deposits)
        }

    @Test
    fun aDepositTheWalletCannotPayLeavesNothingAttempted() =
        runTest {
            val h = Harness()
            val record = h.accepted()
            h.zcash.unpayable = true

            assertFailsWith<AtomicSwapBlockedException> { h.driver.advance(record) }
            assertEquals(SwapDeposit.NotStarted, h.store.record!!.deposit)

            h.zcash.unpayable = false
            h.chain.now = h.chain.t0 - 60
            assertEquals(
                AtomicSwapStep.Finished(AtomicSwapOutcome.NothingSent(NothingSentCause.DEPOSIT_WINDOW_MISSED)),
                h.driver.advance(h.store.record!!),
            )
        }

    @Test
    fun aDepositIsSentAgainUntilItIsMined() =
        runTest {
            val h = Harness()
            val record = h.depositedRecord()
            h.driver.advance(record)
            assertEquals(listOf(txId("deposit1"), txId("deposit1")), h.zcash.submitted())

            h.zcash.status[txId("deposit1")] = ZcashTransactionStatus.Mined(1)
            h.driver.advance(record)
            assertEquals(2, h.zcash.submitted().size)
            assertEquals(1, h.zcash.deposits.size)
        }

    @Test
    fun anExpiredDepositIsPaidAgainOnlyWhileThereIsTimeBeforeT0() =
        runTest {
            val h = Harness()
            val record = h.depositedRecord()
            h.zcash.status[txId("deposit1")] = ZcashTransactionStatus.Expired

            assertEquals(confirming(), h.driver.advance(record))
            assertEquals(2, h.zcash.deposits.size)
            assertEquals(txId("deposit2"), h.store.depositTxId)

            h.zcash.status[txId("deposit2")] = ZcashTransactionStatus.Expired
            h.chain.now = h.chain.t0 - 60
            assertEquals(
                AtomicSwapStep.Finished(AtomicSwapOutcome.NothingSent(NothingSentCause.DEPOSIT_WINDOW_MISSED)),
                h.driver.advance(h.store.record!!),
            )
            assertEquals(2, h.zcash.deposits.size)
        }

    @Test
    fun nothingIsDepositedForAPayoutToSomeoneElse() =
        runTest {
            val h = Harness()
            val record = h.accepted()
            h.chain.payoutNote = NoteCommitment.of(ByteArray(32) { 6 })

            assertEquals(
                AtomicSwapStep.Finished(AtomicSwapOutcome.NothingSent(NothingSentCause.MISMATCH)),
                h.driver.advance(record),
            )
            assertTrue(h.zcash.deposits.isEmpty())
        }

    @Test
    fun nothingIsDepositedWhenT0IsTooFarOut() =
        runTest {
            val h = Harness()
            h.chain.t0 = NOW + 3 * 60 * 60
            val record = h.accepted()

            assertEquals(
                AtomicSwapStep.Finished(AtomicSwapOutcome.NothingSent(NothingSentCause.MISMATCH)),
                h.driver.advance(record),
            )
            assertTrue(h.zcash.deposits.isEmpty())
        }

    @Test
    fun nothingIsDepositedWhenT0IsTooSoon() =
        runTest {
            val h = Harness()
            h.chain.t0 = NOW + 60
            val record = h.accepted()

            assertEquals(
                AtomicSwapStep.Finished(AtomicSwapOutcome.NothingSent(NothingSentCause.DEPOSIT_WINDOW_MISSED)),
                h.driver.advance(record),
            )
            assertTrue(h.zcash.deposits.isEmpty())
        }

    @Test
    fun nothingIsDepositedIntoASwapTheMakerIsCallingOff() =
        runTest {
            val h = Harness()
            val record = h.accepted()
            h.chain.refundLockUntil = NOW + LOCK_SECONDS

            assertEquals(
                AtomicSwapStep.Finished(AtomicSwapOutcome.NothingSent(NothingSentCause.MAKER_CANCELLED)),
                h.driver.advance(record),
            )
            assertTrue(h.zcash.deposits.isEmpty())
        }

    @Test
    fun aStartedDepositTheWalletNeverCreatedIsNothingSentOnceTooLate() =
        runTest {
            val h = Harness()
            val record = h.accepted().copy(deposit = SwapDeposit.Started)
            h.chain.now = h.chain.t0 - 60

            assertEquals(
                AtomicSwapStep.Finished(AtomicSwapOutcome.NothingSent(NothingSentCause.DEPOSIT_WINDOW_MISSED)),
                h.driver.advance(record),
            )
            assertTrue(h.zcash.deposits.isEmpty())
        }

    @Test
    fun aDepositedSwapWaitsForConfirmationsUntilT0() =
        runTest {
            val h = Harness()
            val record = h.depositedRecord()

            assertEquals(confirming(), h.driver.advance(record))
            assertEquals(listOf("/v1/info", "/v1/quote", "/v1/terms", "/v1/quote/$QUOTE_ID/accept"), h.paths)
        }

    @Test
    fun nothingIsDepositedIntoASwapWhoseTermsAreOffByOneInAnyWord() =
        runTest {
            val tampers =
                listOf<(SwapTerms) -> SwapTerms>(
                    { it.copy(maker = it.maker.plusOne()) },
                    { it.copy(token = it.token.plusOne()) },
                    { it.copy(amount = Usdc6(it.amount.micros + bigIntegerOne)) },
                    { it.copy(makerKey = it.makerKey.plusOne(word = 0)) },
                    { it.copy(makerKey = it.makerKey.plusOne(word = 1)) },
                    { it.copy(userKey = it.userKey.plusOne(word = 0)) },
                    { it.copy(userKey = it.userKey.plusOne(word = 1)) },
                    { it.copy(user = it.user.plusOne()) },
                    { it.copy(t0 = it.t0 + 1) },
                    { it.copy(t1 = it.t1 + 1) },
                    { it.copy(payoutNote = NoteCommitment.of(it.payoutNote.bytes.plusOne())) },
                )
            for (tamper in tampers) {
                val h = Harness()
                val record = h.accepted()
                h.chain.tamper = tamper

                assertEquals(
                    AtomicSwapStep.Finished(AtomicSwapOutcome.NothingSent(NothingSentCause.MISMATCH)),
                    h.driver.advance(record),
                )
                assertTrue(h.zcash.deposits.isEmpty())
            }
        }

    @Test
    fun aSilentMakerIsClaimedFromOnceT0Passes() =
        runTest {
            val h = Harness()
            val record = h.depositedRecord()
            h.chain.now = h.chain.t0
            h.clock = h.chain.t0

            assertEquals(AtomicSwapStep.Finished(AtomicSwapOutcome.Paid), h.driver.advance(record))
            assertTrue("/v1/claim" in h.paths)
        }
}

private fun ByteArray.plusOne(at: Int = lastIndex) = copyOf().also { it[at] = (it[at] + 1).toByte() }

private fun Address.plusOne() = Address.fromBytes(bytes.plusOne())

private fun SwapShare.plusOne(word: Int) = SwapShare.of(bytes.plusOne(word * SWAP_WORD_BYTES + SWAP_WORD_BYTES - 1))
