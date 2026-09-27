// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.atomicswap

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.math.bigIntegerValueOf
import xyz.justzappit.evm.types.Address
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AtomicSwapDriverTest {
    @Test
    fun happyPathClaimsIntoRailgun() =
        runTest {
            val h = Harness()
            val record = h.accepted()

            assertEquals(confirming(), h.driver.advance(record))
            assertEquals(listOf("utest1deposit" to DEPOSIT_ZAT), h.zcash.payments)
            assertEquals("0xdeposit", h.store.record?.depositTxId)

            h.chain.stage = SwapStage.READY
            val step = h.driver.advance(h.store.record!!)

            assertEquals(AtomicSwapStep.Finished(AtomicSwapOutcome.Paid), step)
            assertEquals(
                listOf(
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
            assertEquals(AtomicSwapOutcome.Paid, h.store.record?.outcome)
            assertEquals(h.clock, h.store.record?.finishedAt)
            assertEquals("0x1", h.store.record?.payoutTx, "the claim's second transaction is the payout")
        }

    @Test
    fun anOfferSaysWhatArrivesAfterTheRelayerAndRailgunTakeTheirFees() =
        runTest {
            val offer = Harness().driver.quote(units = 1)

            assertEquals(0, offer.relayerFee.compareTo(bigIntegerValueOf(20_000)))
            assertEquals(0, offer.receives.compareTo(bigIntegerValueOf(977_550)))
            assertEquals(0, offer.index)
        }

    @Test
    fun eachQuoteTakesItsOwnIndex() =
        runTest {
            val h = Harness()
            h.driver.quote(units = 1)
            assertEquals(1, h.driver.quote(units = 1).index)
        }

    @Test
    fun aQuoteForAnotherDeploymentIsRefused() =
        runTest {
            val h = Harness()
            h.quoteJson = QUOTE_JSON.replace("11155111", "1")

            val refused = assertFailsWith<AtomicSwapBlockedException> { h.driver.quote(units = 1) }
            assertEquals(AtomicSwapBlock.WRONG_DEPLOYMENT, refused.reason)
        }

    @Test
    fun aQuoteAboutToRunOutIsNotAccepted() =
        runTest {
            val h = Harness()
            val offer = h.driver.quote(units = 1)
            h.clock = NOW + 290

            val refused = assertFailsWith<AtomicSwapBlockedException> { h.driver.accept(offer) }
            assertEquals(AtomicSwapBlock.QUOTE_EXPIRED, refused.reason)
            assertTrue(h.paths.none { it.endsWith("/accept") })
            assertNull(h.store.record)
        }

    @Test
    fun aQuoteTheMakerNoLongerKnowsEndsWithNothingSent() =
        runTest {
            val h = Harness()
            h.acceptStatus = HttpStatusCode.NotFound

            val record = h.driver.accept(h.driver.quote(units = 1))

            assertEquals(AtomicSwapOutcome.NothingSent(NothingSentCause.QUOTE_EXPIRED), record.outcome)
            assertEquals(record, h.store.record)
        }

    @Test
    fun aMakerWithoutInventoryEndsWithNothingSent() =
        runTest {
            val h = Harness()
            h.acceptStatus = HttpStatusCode.ServiceUnavailable

            val record = h.driver.accept(h.driver.quote(units = 1))

            assertEquals(AtomicSwapOutcome.NothingSent(NothingSentCause.MAKER_UNAVAILABLE), record.outcome)
        }

    @Test
    fun anAcceptThatMayHaveOpenedWaitsForTheSwapThenGivesUp() =
        runTest {
            val h = Harness()
            h.acceptStatus = HttpStatusCode.InternalServerError
            h.chain.onChain = false

            assertFailsWith<AtomicSwapHttpException> { h.driver.accept(h.driver.quote(units = 1)) }
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

            val refused = assertFailsWith<AtomicSwapBlockedException> { h.driver.quote(units = 1) }
            assertEquals(AtomicSwapBlock.SWAP_UNDER_WAY, refused.reason)
        }

    @Test
    fun anInterruptedDepositThatWentOutIsRecordedNotPaidAgain() =
        runTest {
            val h = Harness()
            val record = h.accepted().copy(depositAttempted = true)
            h.zcash.sent["utest1deposit"] = "0xearlier"

            assertEquals(confirming(), h.driver.advance(record))
            assertTrue(h.zcash.payments.isEmpty())
            assertEquals("0xearlier", h.store.record?.depositTxId)
        }

    @Test
    fun anInterruptedDepositThatNeverWentOutIsPaidOnce() =
        runTest {
            val h = Harness()
            val record = h.accepted().copy(depositAttempted = true)

            assertEquals(confirming(), h.driver.advance(record))
            assertEquals("0xdeposit", h.store.record?.depositTxId)
            assertEquals(listOf("utest1deposit" to DEPOSIT_ZAT), h.zcash.payments)
        }

    @Test
    fun nothingIsDepositedForAPayoutToSomeoneElse() =
        runTest {
            val h = Harness()
            val record = h.accepted()
            h.chain.payoutNote = ByteArray(32) { 6 }

            assertEquals(
                AtomicSwapStep.Finished(AtomicSwapOutcome.NothingSent(NothingSentCause.MISMATCH)),
                h.driver.advance(record),
            )
            assertTrue(h.zcash.payments.isEmpty())
        }

    @Test
    fun nothingIsDepositedWhenT0IsTooFarOut() =
        runTest {
            val h = Harness()
            val record = h.accepted()
            h.chain.t0 = NOW + 3 * 60 * 60

            assertEquals(
                AtomicSwapStep.Finished(AtomicSwapOutcome.NothingSent(NothingSentCause.MISMATCH)),
                h.driver.advance(record),
            )
            assertTrue(h.zcash.payments.isEmpty())
        }

    @Test
    fun nothingIsDepositedWhenT0IsTooSoon() =
        runTest {
            val h = Harness()
            val record = h.accepted()
            h.chain.t0 = NOW + 60

            assertEquals(
                AtomicSwapStep.Finished(AtomicSwapOutcome.NothingSent(NothingSentCause.DEPOSIT_WINDOW_MISSED)),
                h.driver.advance(record),
            )
            assertTrue(h.zcash.payments.isEmpty())
        }

    @Test
    fun aStartedDepositThatIsTooLateToFinishWaitsForTheMakerToCallItOff() =
        runTest {
            val h = Harness()
            val record = h.accepted().copy(depositAttempted = true)
            h.chain.t0 = NOW + 60

            assertEquals(
                AtomicSwapStep.Waiting(AtomicSwapWait.DEPOSIT_UNSETTLED, h.chain.t0, h.chain.t0 + 300),
                h.driver.advance(record),
            )
            assertTrue(h.zcash.payments.isEmpty())
        }

    @Test
    fun aDepositedSwapWaitsForConfirmationsUntilT0() =
        runTest {
            val h = Harness()
            val record = h.depositedRecord()

            assertEquals(confirming(), h.driver.advance(record))
            assertEquals(listOf("/v1/quote", "/v1/terms", "/v1/quote/$QUOTE_ID/accept"), h.paths)
        }

    @Test
    fun aSilentMakerIsClaimedFromOnceT0Passes() =
        runTest {
            val h = Harness()
            val record = h.depositedRecord()
            h.chain.now = h.chain.t0

            assertEquals(AtomicSwapStep.Finished(AtomicSwapOutcome.Paid), h.driver.advance(record))
            assertTrue("/v1/claim" in h.paths)
        }

    @Test
    fun aRefundAfterADepositThatNeverWentOutSweepsNothing() =
        runTest {
            val h = Harness()
            val record = h.accepted().copy(depositAttempted = true)
            h.chain.stage = SwapStage.REFUNDED

            assertEquals(
                AtomicSwapStep.Finished(AtomicSwapOutcome.NothingSent(NothingSentCause.MAKER_CANCELLED)),
                h.driver.advance(record),
            )
            assertTrue(h.zcash.sweeps.isEmpty())
        }

    @Test
    fun aRefundAfterAnInterruptedDepositThatWentOutSweepsIt() =
        runTest {
            val h = Harness()
            val record = h.accepted().copy(depositAttempted = true)
            h.zcash.sent["utest1deposit"] = "0xearlier"
            h.chain.stage = SwapStage.REFUNDED

            assertEquals(
                AtomicSwapStep.Finished(AtomicSwapOutcome.Refunded("0xsweep", RefundCause.MAKER_CANCELLED)),
                h.driver.advance(record),
            )
            assertEquals(1, h.zcash.sweeps.size)
        }

    @Test
    fun aRefundSweepsTheDepositHome() =
        runTest {
            val h = Harness()
            val record = h.depositedRecord()
            h.chain.stage = SwapStage.REFUNDED
            h.chain.secret = ByteArray(32) { 0x0e }

            assertEquals(
                AtomicSwapStep.Finished(AtomicSwapOutcome.Refunded("0xsweep", RefundCause.MAKER_CANCELLED)),
                h.driver.advance(record),
            )
            assertEquals(listOf(Triple(record.index, MAKER_SHARE.toList(), record.zcashHeight)), h.zcash.sweeps)
        }

    @Test
    fun aRefundLockedAfterT0IsASwapNobodyClaimedInTime() =
        runTest {
            val h = Harness()
            val record = h.depositedRecord()
            h.chain.stage = SwapStage.REFUNDED
            h.chain.refundLockUntil = h.chain.t0 + 300 + 10 + LOCK_SECONDS

            assertEquals(
                AtomicSwapStep.Finished(AtomicSwapOutcome.Refunded("0xsweep", RefundCause.NOT_CLAIMED_IN_TIME)),
                h.driver.advance(record),
            )
        }

    @Test
    fun theShareStaysSecretWhenRailgunIsNotTakingPayouts() =
        runTest {
            val h = Harness()
            val record = h.depositedRecord()
            h.chain.stage = SwapStage.READY
            h.chain.railgunAccepts = false

            val blocked = assertFailsWith<AtomicSwapBlockedException> { h.driver.advance(record) }
            assertEquals(AtomicSwapBlock.RAILGUN_CLOSED, blocked.reason)
            assertTrue("/v1/claim" !in h.paths)
        }

    @Test
    fun theShareStaysSecretUnderALockAboutToLapse() =
        runTest {
            val h = Harness()
            val record = h.depositedRecord()
            h.chain.stage = SwapStage.READY
            h.chain.claimLockUntil = NOW + 60

            val blocked = assertFailsWith<AtomicSwapBlockedException> { h.driver.advance(record) }
            assertEquals(AtomicSwapBlock.CLAIM_LOCK_LAPSING, blocked.reason)
            assertTrue("/v1/claim" !in h.paths)
        }

    @Test
    fun anUnreachableRelayerIsReportedAsSuch() =
        runTest {
            val h = Harness()
            val record = h.depositedRecord()
            h.chain.stage = SwapStage.READY
            h.unreachable = "/v1/lock-claim"

            val failed = assertFailsWith<AtomicSwapHttpException> { h.driver.advance(record) }
            assertEquals(AtomicSwapService.RELAYER, failed.service)
            assertNull(failed.status)
            assertNull(h.store.record?.outcome)
        }

    @Test
    fun aClaimRevealedBeforeAnInterruptionOnlyNeedsItsPayout() =
        runTest {
            val h = Harness()
            val record = h.depositedRecord()
            h.chain.stage = SwapStage.CLAIMED

            assertEquals(AtomicSwapStep.Finished(AtomicSwapOutcome.Paid), h.driver.advance(record))
            assertEquals("/v1/payout", h.paths.last())
            assertTrue("/v1/claim" !in h.paths)
            assertEquals("0x0", h.store.record?.payoutTx)
        }

    @Test
    fun onlyASwapThatNeverReachedTheChainCanBeAbandoned() =
        runTest {
            val h = Harness()
            val record = h.accepted()

            val refused = assertFailsWith<AtomicSwapBlockedException> { h.driver.abandon(record) }
            assertEquals(AtomicSwapBlock.UNDER_WAY_ON_CHAIN, refused.reason)

            h.chain.onChain = false
            assertEquals(
                AtomicSwapStep.Finished(AtomicSwapOutcome.NothingSent(NothingSentCause.NEVER_OPENED)),
                h.driver.abandon(record),
            )
        }

    @Test
    fun slowStepsAreAnnouncedBeforeTheyStart() =
        runTest {
            val h = Harness()
            val heard = mutableListOf<AtomicSwapActivity>()
            h.driver.advance(h.accepted(), heard::add)
            h.chain.stage = SwapStage.READY
            h.driver.advance(h.store.record!!, heard::add)

            assertEquals(listOf(AtomicSwapActivity.DEPOSITING, AtomicSwapActivity.CLAIMING), heard)
        }

    @Test
    fun aFinishedSwapStaysFinished() =
        runTest {
            val h = Harness()
            val record = h.depositedRecord().copy(outcome = AtomicSwapOutcome.Paid)

            assertEquals(AtomicSwapStep.Finished(AtomicSwapOutcome.Paid), h.driver.advance(record))
        }

    private fun confirming() = AtomicSwapStep.Waiting(AtomicSwapWait.CONFIRMING, NOW + 35 * 60, NOW + 35 * 60 + 300)

    private class Harness {
        val chain = FakeChain()
        val zcash = FakeZcash()
        val store = FakeStore()
        val paths = mutableListOf<String>()
        val bodies = mutableMapOf<String, String>()
        var clock = NOW
        var quoteJson = QUOTE_JSON
        var acceptStatus = HttpStatusCode.OK
        var unreachable: String? = null
        private val engine =
            MockEngine { request ->
                val path = request.url.encodedPath
                paths += path
                bodies[path] = (request.body as? TextContent)?.text.orEmpty()
                if (path == unreachable) throw IOException("connection refused")
                val status = if (path.endsWith("/accept")) acceptStatus else HttpStatusCode.OK
                val body =
                    when {
                        status != HttpStatusCode.OK -> {
                            """{"error":"${status.description}"}"""
                        }

                        path == "/v1/quote" -> {
                            quoteJson
                        }

                        path.endsWith("/accept") -> {
                            """{"swapId":"${SWAP_ID.hex()}"}"""
                        }

                        path == "/v1/terms" -> {
                            TERMS_JSON
                        }

                        path == "/v1/lock-claim" -> {
                            sent(1).also { chain.claimLockUntil = chain.now + LOCK_SECONDS }
                        }

                        path == "/v1/claim" -> {
                            sent(2).also {
                                chain.stage = SwapStage.CLAIMED
                                chain.paidOut = true
                            }
                        }

                        path == "/v1/payout" -> {
                            sent(1).also { chain.paidOut = true }
                        }

                        else -> {
                            error("unexpected $path")
                        }
                    }
                respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        private val http = HttpClient(engine)
        val driver =
            AtomicSwapDriver(
                config = CONFIG,
                maker = MakerClient(http, "http://maker"),
                relayer = RelayerClient(http, "http://relayer"),
                chain = chain,
                keys = FakeKeys(),
                zcash = zcash,
                store = store,
                nowSeconds = { clock },
            )

        suspend fun accepted(): AtomicSwapRecord = driver.accept(driver.quote(units = 1))

        suspend fun depositedRecord(): AtomicSwapRecord {
            driver.advance(accepted())
            return store.record!!
        }

        private fun sent(transactions: Int): String {
            val hashes = List(transactions) { "\"0x$it\"" }.joinToString()
            return """{"transactions":[$hashes]}"""
        }
    }

    private class FakeChain : AtomicSwapChainReader {
        var onChain = true
        var now = NOW
        var stage = SwapStage.OPEN
        var t0 = NOW + 35 * 60
        var claimLockUntil = 0L
        var refundLockUntil = 0L
        var paidOut = false
        var payoutNote = NOTE_COMMITMENT
        var secret = ByteArray(32)
        var railgunAccepts = true

        override suspend fun swap(id: ByteArray): OnChainSwap? =
            OnChainSwap(
                maker = MAKER,
                t0 = t0,
                stage = stage,
                paidOut = paidOut,
                user = AUTH,
                t1 = t0 + 300,
                token = TOKEN,
                claimLockUntil = claimLockUntil,
                amount = AMOUNT,
                refundLockUntil = refundLockUntil,
                makerShare = MAKER_SHARE,
                userShare = USER_SHARE,
                secret = secret,
                payoutNote = payoutNote,
            ).takeIf { onChain }

        override suspend fun now() = now

        override suspend fun railgunAccepts(token: Address) = railgunAccepts

        override suspend fun lockDuration() = LOCK_SECONDS.toLong()
    }

    private class FakeKeys : AtomicSwapKeys {
        override suspend fun userShare(index: Int) = USER_SHARE

        override suspend fun authAddress(index: Int) = AUTH

        override suspend fun payoutNote(index: Int) =
            PayoutNote(ByteArray(32) { 1 }, List(3) { ByteArray(32) { 2 } }, ByteArray(32) { 3 }, NOTE_COMMITMENT)

        override suspend fun accept(
            index: Int,
            chainId: Long,
            contract: Address,
            quoteId: ByteArray,
            makerShare: ByteArray,
            makerProof: ByteArray,
        ) = UserAcceptance(USER_SHARE, ByteArray(64) { 4 }, ByteArray(64) { 5 })

        override suspend fun depositAddress(
            index: Int,
            makerShare: ByteArray
        ) = "utest1deposit"

        override suspend fun claimSecret(index: Int) = ByteArray(32) { 9 }

        override suspend fun signLockClaim(
            index: Int,
            chainId: Long,
            contract: Address,
            swapId: ByteArray,
            deadline: Long,
        ) = ByteArray(65) { 7 }

        override suspend fun signPayout(
            index: Int,
            chainId: Long,
            contract: Address,
            swapId: ByteArray,
            relayer: Address,
            fee: BigInteger,
        ) = ByteArray(65) { 8 }
    }

    private class FakeZcash : AtomicSwapZcash {
        val payments = mutableListOf<Pair<String, Long>>()
        val sweeps = mutableListOf<Triple<Int, List<Byte>, Long>>()

        /** Payments already in the wallet's history, by address. */
        val sent = mutableMapOf<String, String>()

        override suspend fun chainHeight() = 4_200_000L

        override suspend fun pay(
            address: String,
            zatoshi: Long
        ): String {
            payments += address to zatoshi
            return "0xdeposit"
        }

        override suspend fun findPayment(address: String) = sent[address]

        override suspend fun sweepRefund(
            index: Int,
            makerShare: ByteArray,
            makerSecret: ByteArray,
            birthday: Long,
        ): String {
            sweeps += Triple(index, makerShare.toList(), birthday)
            return "0xsweep"
        }
    }

    private class FakeStore : AtomicSwapStore {
        var record: AtomicSwapRecord? = null
        private var next = 0

        override suspend fun takeIndex() = next++

        override suspend fun active() = record

        override suspend fun save(record: AtomicSwapRecord) {
            this.record = record
        }
    }

    private companion object {
        const val NOW = 1_790_000_000L
        const val LOCK_SECONDS = 600
        const val DEPOSIT_ZAT = 202_021L
        const val QUOTE_ID = "0x" + "22222222222222222222222222222222" + "22222222222222222222222222222222"
        val MAKER = Address.parse("0x09eD1F966745Be18C711C346242c0974DAd7c3e5")
        val AUTH = Address.parse("0x4444444444444444444444444444444444444444")
        val CONTRACT = Address.parse("0x32CE55D00E6184c385E44e6b20b76d3a8407E809")
        val TOKEN = Address.parse("0x5764D0044bef5AA839E0dDafE2073421101B9Ed8")
        val AMOUNT = bigIntegerValueOf(1_000_000)
        val USER_SHARE = ByteArray(64) { 0x0c }
        val MAKER_SHARE = ByteArray(64) { 0x0a }
        val NOTE_COMMITMENT = ByteArray(32) { 0x05 }
        val SWAP_ID = AtomicSwapChain.swapId(MAKER, USER_SHARE)
        val CONFIG =
            AtomicSwapConfig(
                makerUrl = "http://maker",
                relayerUrl = "http://relayer",
                chainId = 11_155_111,
                contract = CONTRACT,
                token = TOKEN,
                railgunProxy = Address.parse("0xeCFCf3b4eC647c4Ca6D49108b311b7a7C9543fea"),
                maxRelayerFee = bigIntegerValueOf(100_000),
            )
        val QUOTE_JSON =
            """
            {"quoteId":"$QUOTE_ID","maker":"${MAKER.checksumHex}","makerShare":"${MAKER_SHARE.hex()}",
             "makerProof":"${ByteArray(64) { 6 }.hex()}","chainId":11155111,"contract":"${CONTRACT.checksumHex}",
             "token":"${TOKEN.checksumHex}","amount":"1000000","depositZat":$DEPOSIT_ZAT,"expiresAt":${NOW + 300}}
            """.trimIndent()
        val TERMS_JSON =
            """{"relayer":"0x507d1d152025e9f6da7bc03b358acc247f07b4eb","chainId":11155111,""" +
                """"contract":"${CONTRACT.lowercaseHex}","fee":"20000"}"""
    }
}
