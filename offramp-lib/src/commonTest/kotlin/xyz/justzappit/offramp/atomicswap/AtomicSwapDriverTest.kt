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
    fun theQuoteNamesTheNoteThePayoutGoesTo() =
        runTest {
            val h = Harness()
            h.accepted()

            assertTrue(h.bodies.getValue("/v1/quote").contains(NOTE_COMMITMENT.hex))
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
    fun theAcceptsTicketIsKeptBeforeAnyRelayerRequestAndEachOfThemCarriesIt() =
        runTest {
            val h = Harness()
            h.claimPaysOut = false
            val record = h.accepted()

            assertEquals(TICKET, record.ticket)
            assertEquals(TICKET, h.store.record?.ticket, "kept with the deadlines, before any relayer request")
            assertTrue(SWAP_REQUESTS.none { it in h.paths })
            h.driver.advance(record)
            h.chain.stage = SwapStage.READY
            assertEquals(AtomicSwapStep.Finished(AtomicSwapOutcome.Paid), h.driver.advance(h.store.record!!))

            assertEquals(SWAP_REQUESTS.associateWith { TICKET }, h.tickets.filterKeys { it in SWAP_REQUESTS })
            assertNull(h.tickets.getValue("/v1/terms"), "the relayer's terms are no swap's")
        }

    @Test
    fun aSwapWithoutATicketOfItsOwnClaimsWithoutOne() =
        runTest {
            // An older maker's answer, and a ticket for another swap.
            for (ticket in listOf(null, "${hash(7)}." + "3f".repeat(32))) {
                val h = Harness()
                h.claimPaysOut = false
                h.acceptTicket = ticket
                val record = h.accepted()

                assertNull(record.ticket)
                h.driver.advance(record)
                h.chain.stage = SwapStage.READY
                assertEquals(AtomicSwapStep.Finished(AtomicSwapOutcome.Paid), h.driver.advance(h.store.record!!))
                assertEquals(SWAP_REQUESTS.associateWith { null }, h.tickets.filterKeys { it in SWAP_REQUESTS })
            }
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
    fun nothingIsDepositedIntoASwapTheMakerCalledOffInTheNewestBlock() =
        runTest {
            val h = Harness()
            val record = h.accepted()
            h.chain.latest = { h.chain.current()?.withStage(SwapStage.REFUNDED) }

            assertEquals(
                AtomicSwapStep.Finished(AtomicSwapOutcome.NothingSent(NothingSentCause.MAKER_CANCELLED)),
                h.driver.advance(record),
            )
            assertTrue(h.zcash.deposits.isEmpty())
        }

    @Test
    fun aKeptDepositIsSentAgainOnlyWhileTheSwapIsOpen() =
        runTest {
            val h = Harness()
            val record = h.depositedRecord()
            h.chain.latest = { h.chain.current()?.withStage(SwapStage.REFUNDED) }

            h.driver.advance(record)

            assertEquals(listOf(txId("deposit1")), h.zcash.submitted(), "called off: left to be mined or expire")
            assertEquals(1, h.zcash.deposits.size)
        }

    @Test
    fun nothingIsDepositedIntoASwapThatLeavesNoTimeToClaimFromT0OrHoldsTheDepositPastTwoHours() =
        runTest {
            for (offset in listOf(2 * CLAIM_MARGIN_SECONDS - 1, 2 * 60 * 60L + 1)) {
                val h = Harness()
                h.chain.t1Offset = offset
                val record = h.accepted()

                assertEquals(
                    AtomicSwapStep.Finished(AtomicSwapOutcome.NothingSent(NothingSentCause.MISMATCH)),
                    h.driver.advance(record),
                    "t1 $offset after t0",
                )
                assertTrue(h.zcash.deposits.isEmpty())
            }
            val h = Harness()
            h.chain.t1Offset = 2 * CLAIM_MARGIN_SECONDS
            assertEquals(confirming().copy(t1 = h.chain.t1), h.driver.advance(h.accepted()))
        }

    @Test
    fun aDepositStartsOnlyWithinItsWindowFromTheAccept() =
        runTest {
            val h = Harness()
            h.chain.t0 = NOW + 60 * 60
            val record = h.accepted()
            h.clock = NOW + 10 * 60 + 1

            assertEquals(
                AtomicSwapStep.Finished(AtomicSwapOutcome.NothingSent(NothingSentCause.DEPOSIT_WINDOW_MISSED)),
                h.driver.advance(record),
            )
            assertTrue(h.zcash.deposits.isEmpty())
        }

    @Test
    fun aDeviceClockAheadOfTheChainCanOnlyCloseTheDepositsWindowSooner() =
        runTest {
            val ahead = Harness()
            ahead.chain.t0 = NOW + 30 * 60
            val record = ahead.accepted()
            // Six minutes ahead, t0 is less than the 25 minutes a deposit needs away.
            ahead.clock = NOW + 6 * 60

            assertEquals(
                AtomicSwapStep.Finished(AtomicSwapOutcome.NothingSent(NothingSentCause.DEPOSIT_WINDOW_MISSED)),
                ahead.driver.advance(record),
            )
            assertTrue(ahead.zcash.deposits.isEmpty())
            val inStep = Harness()
            inStep.chain.t0 = NOW + 30 * 60
            inStep.driver.advance(inStep.accepted())
            assertEquals(1, inStep.zcash.deposits.size)
        }

    @Test
    fun anAcceptNamingAnotherLateWindowThanTheContractsEndsWithNothingSent() =
        runTest {
            val h = Harness()
            h.acceptLateWindow = LATE_WINDOW + 1

            val record = h.accepted()

            assertEquals(AtomicSwapOutcome.NothingSent(NothingSentCause.MISMATCH), record.outcome)
            assertEquals(TICKET, record.ticket, "kept with the deadlines all the same")
            val older = Harness()
            older.acceptLateWindow = null
            assertNull(older.accepted().outcome, "an older maker's answer says nothing of it")
        }

    @Test
    fun aMakerOrRelayerNamingAnotherKindOrOtherWindowsIsRefused() =
        runTest {
            val other =
                listOf(
                    ""","contractKind":"locks"""",
                    ""","minWindow":${MIN_WINDOW + 1}""",
                    ""","lateWindow":${LATE_WINDOW * 24}""",
                )
            for (named in other) {
                val h = Harness()
                h.contractJson = named
                val refused = assertFailsWith<AtomicSwapBlockedException> { h.driver.quote(ONE_UNIT) }
                assertEquals(AtomicSwapBlock.WRONG_DEPLOYMENT, refused.reason, named)
                assertEquals(listOf("/v1/info"), h.paths)
            }
            val h = Harness()
            h.contractJson = ""
            assertEquals(0, h.driver.quote(ONE_UNIT).index, "one that says nothing of it fits")
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
            h.at(h.chain.t0 - 1)
            assertEquals(confirming(), h.driver.advance(record))
            assertTrue("/v1/claim" !in h.paths, "before t0 an unready swap is the maker's to call off")

            h.at(h.chain.t0)
            assertEquals(AtomicSwapStep.Finished(AtomicSwapOutcome.Paid), h.driver.advance(record))
            assertTrue("/v1/claim" in h.paths)
        }

    @Test
    fun aRetiredContractIsNeverQuotedOrPaidInto() =
        runTest {
            val h = Harness(DEPLOYMENT.copy(windows = null))

            val refused = assertFailsWith<AtomicSwapBlockedException> { h.driver.quote(ONE_UNIT) }
            assertEquals(AtomicSwapBlock.RETIRED_CONTRACT, refused.reason)
            assertTrue(h.paths.isEmpty())
        }

    @Test
    fun aSwapOnARetiredContractIsHistoryUnlessItsMakersRefundBringsTheDepositHome() =
        runTest {
            val open = Harness()
            val unpaid = open.accepted()
            val retired = Harness(DEPLOYMENT.copy(windows = null))
            retired.chain.opened = true
            retired.store.record = unpaid

            assertEquals(
                AtomicSwapStep.Finished(AtomicSwapOutcome.NothingSent(NothingSentCause.MAKER_UNAVAILABLE)),
                retired.driver.advance(unpaid),
            )
            val paid = unpaid.copy(deposit = SwapDeposit.Kept(transaction("deposit1")))
            retired.store.record = paid
            retired.chain.stage = SwapStage.READY
            assertEquals(AtomicSwapStep.Waiting(AtomicSwapWait.RETIRED), retired.driver.advance(paid))
            assertTrue(retired.paths.isEmpty(), "no relayer is asked anything")
            assertEquals(0, retired.chain.reads)
            assertTrue(retired.zcash.submitted().isEmpty())

            retired.chain.stage = SwapStage.REFUNDED
            retired.zcash.status[txId("deposit1")] = ZcashTransactionStatus.Mined(5)
            assertEquals(AtomicSwapStep.Waiting(AtomicSwapWait.REFUNDING), retired.driver.advance(paid))
            assertEquals(1, retired.zcash.sweeps.size)
        }
}

/** [this] state at [stage]. */
internal fun SwapState.withStage(stage: SwapStage) = SwapState(termsHash, stage, paidOut, secret)

private fun ByteArray.plusOne(at: Int = lastIndex) = copyOf().also { it[at] = (it[at] + 1).toByte() }

private fun Address.plusOne() = Address.fromBytes(bytes.plusOne())

private fun SwapShare.plusOne(word: Int) = SwapShare.of(bytes.plusOne(word * SWAP_WORD_BYTES + SWAP_WORD_BYTES - 1))

/** A forward swap's requests to the relayer, each for the swap. */
private val SWAP_REQUESTS = listOf("/v1/claim", "/v1/payout")
