// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.atomicswap

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.types.Address
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReverseSwapDriverTest {
    @Test
    fun quotePreviewSurvivesRestartWithoutAcceptingOrFunding() =
        runTest {
            val h = Harness()
            h.driver.quote(1_000_000)
            h.restart()
            h.driver.advance()
            assertEquals(ReversePhase.QUOTED, h.record.phase)
            assertFalse(h.record.underWay)
            assertNull(h.record.account)
            assertEquals(0, h.prepares)
            assertFailsWith<IllegalStateException> { h.driver.fund(0) }
            h.driver.quote(1_000_000)
            assertEquals(1, h.record.index)
            assertEquals(2, h.nextIndex)
        }

    @Test
    fun twoAuthorizationsAndConfirmedReceive() =
        runTest {
            val h = Harness()
            h.prepared()
            assertEquals(ReversePhase.AWAITING_FUNDING, h.record.phase)
            assertTrue(h.record.underWay)
            assertEquals(0, h.prepares)
            h.driver.fund(0)
            assertEquals(1, h.prepares)
            assertNotNull(h.record.funding)
            h.funded()
            h.spendable = DEPOSIT
            h.driver.advance()
            assertEquals(ReversePhase.AWAITING_READY, h.record.phase)
            assertEquals(0, h.readyCalls)
            h.driver.ready(0)
            assertEquals(1, h.readyCalls)
            h.escrow = h.swap(SwapStage.CLAIMED)
            h.driver.advance()
            assertEquals(ReversePhase.RECEIVING, h.record.phase)
            assertEquals(DEPOSIT - 10_000, h.record.receive?.receivedZat)
            assertEquals(1, h.receiveSubmits)
            val transaction = h.record.receive
            h.receiveConfirmations = 1
            h.restart()
            h.driver.advance()
            assertEquals(ReversePhase.RECEIVING, h.record.phase)
            assertEquals(1L, h.record.receiveConfirmations)
            assertEquals(transaction, h.record.receive)
            assertEquals(1, h.receivePrepares)
            assertEquals(1, h.receiveSubmits)
            h.receiveState = ReverseTransactionStatus.CONFIRMED
            h.receiveConfirmations = 10
            h.driver.advance()
            assertEquals(ReversePhase.COMPLETE, h.record.phase)
        }

    @Test
    fun partialAndUnconfirmedDepositsNeverEnableReady() =
        runTest {
            val h = Harness()
            h.prepared()
            h.driver.fund(0)
            h.funded()
            for (amount in listOf(0L, 1L, DEPOSIT - 1)) {
                h.spendable = amount
                h.driver.advance()
                assertEquals(ReversePhase.RECEIVING_ZEC, h.record.phase)
                assertFailsWith<IllegalStateException> { h.driver.ready(0) }
            }
            assertNull(h.record.ready)
            assertEquals(0, h.readyCalls)
        }

    @Test
    fun interruptedFundingReusesPersistedTransactionAfterRestart() =
        runTest {
            val h = Harness()
            h.prepared()
            h.interruptFunding = true
            assertFailsWith<IllegalStateException> { h.driver.fund(0) }
            val saved = h.record.funding
            assertNotNull(saved)
            h.interruptFunding = false
            h.restart()
            h.driver.fund(0)
            h.driver.advance()
            assertEquals(1, h.prepares)
            assertTrue(h.submissions.all { it == saved })
        }

    @Test
    fun duplicateFundingAndReadyCallbacksDoNotCreateAnotherPaymentOrSignature() =
        runTest {
            val h = Harness()
            h.prepared()
            h.driver.fund(0)
            h.driver.fund(0)
            h.funded()
            h.spendable = DEPOSIT
            h.driver.ready(0)
            h.driver.ready(0)
            assertEquals(1, h.prepares)
            assertEquals(1, h.readySignatures)
        }

    @Test
    fun restartNeverAuthorizesReady() =
        runTest {
            val h = Harness()
            h.prepared()
            h.driver.fund(0)
            h.funded()
            h.spendable = DEPOSIT
            h.restart()
            repeat(3) { h.driver.advance() }
            assertEquals(ReversePhase.AWAITING_READY, h.record.phase)
            assertNull(h.record.ready)
            assertEquals(0, h.readySignatures)
        }

    @Test
    fun rolesAndEveryEscrowTermAreVerified() =
        runTest {
            val h = Harness()
            h.prepared()
            h.funded()
            val good = h.state()
            ReverseSwapVerifier.verifyEscrow(h.record, good)
            val forwardRoles =
                OnChainSwap(
                    Address.parse(MAKER),
                    READY,
                    SwapStage.OPEN,
                    false,
                    Address.parse(USER),
                    REFUND,
                    Address.parse(TOKEN),
                    0,
                    BigInteger(AMOUNT),
                    0,
                    fixedHex(MAKER_SHARE, 64),
                    fixedHex(USER_SHARE, 64),
                    ByteArray(32),
                    ByteArray(32)
                )
            assertFailsWith<IllegalStateException> {
                ReverseSwapVerifier.verifyEscrow(
                    h.record,
                    good.copy(swap = forwardRoles)
                )
            }
            assertFailsWith<IllegalStateException> {
                ReverseSwapVerifier.verifyEscrow(h.record, good.copy(refundNote = ByteArray(32)))
            }
            assertFailsWith<IllegalStateException> {
                ReverseSwapVerifier.verifyEscrow(
                    h.record,
                    good.copy(fundingBlock = 0)
                )
            }
        }

    @Test
    fun rejectsMalformedOrMismatchedQuotesBeforeAcceptance() =
        runTest {
            val h = Harness()
            val quote = h.quote
            assertFailsWith<IllegalArgumentException> { quote.copy(refundNote = "0x01") }
            assertFailsWith<IllegalArgumentException> { quote.copy(terms = quote.terms.copy(amount = "1.25")) }
            assertFailsWith<IllegalArgumentException> { quote.copy(terms = quote.terms.copy(amount = "-1")) }
            assertFailsWith<IllegalArgumentException> { quote.copy(terms = quote.terms.copy(makerProof = "0x")) }
            assertFailsWith<IllegalArgumentException> { quote.copy(terms = quote.terms.copy(depositZat = 0)) }
            assertFailsWith<IllegalArgumentException> { quote.copy(readyDeadline = FUNDING) }
            for (bad in listOf(
                quote.copy(user = MAKER),
                quote.copy(refundNote = hex(32, 9)),
                quote.copy(terms = quote.terms.copy(chainId = 1)),
                quote.copy(terms = quote.terms.copy(contract = TOKEN)),
                quote.copy(terms = quote.terms.copy(token = MAKER)),
                quote.copy(terms = quote.terms.copy(maker = USER)),
            )) {
                h.quote = bad
                assertFailsWith<IllegalStateException> { h.driver.quote(1_000_000) }
                assertNull(h.saved)
            }
            h.quote = quote
            h.proofValid = false
            assertFailsWith<IllegalStateException> { h.driver.quote(1_000_000) }
            assertNull(h.saved)
            assertTrue(h.nextIndex > 0)
        }

    @Test
    fun pendingSwapCannotMoveToAnotherDeployment() =
        runTest {
            val h = Harness()
            h.prepared()
            h.saved = h.record.copy(deployment = DEPLOYMENT.copy(contract = USER))
            assertFailsWith<IllegalStateException> { h.driver.advance() }
            assertEquals(0, h.prepares)
        }

    @Test
    fun fundingAndReadyRespectDeadlines() =
        runTest {
            val h = Harness()
            h.prepared()
            h.now = FUNDING
            assertFailsWith<IllegalStateException> { h.driver.fund(0) }
            h.funded()
            h.spendable = DEPOSIT
            h.now = READY - ReverseSwapDriver.SIGNATURE_TTL
            assertFailsWith<IllegalStateException> { h.driver.ready(0) }
            assertNull(h.record.ready)
        }

    @Test
    fun cancellationNeverRevealsWithoutConfirmedLockAndMargin() =
        runTest {
            val h = Harness()
            h.prepared()
            h.driver.fund(0)
            h.funded()
            h.driver.cancel(0)
            assertEquals(1, h.lockCalls)
            assertEquals(0, h.refundCalls)
            h.refundUntil = h.now + ReverseSwapDriver.REVEAL_MARGIN
            h.driver.advance()
            assertEquals(0, h.refundCalls)
            h.refundUntil = h.now + 600
            h.driver.advance()
            assertEquals(1, h.refundCalls)
            assertNotNull(h.record.payout)
        }

    @Test
    fun afterReadyRefundWaitsForDeadlineAndAlternatingTurn() =
        runTest {
            val h = Harness()
            h.prepared()
            h.driver.fund(0)
            h.funded()
            h.escrow = h.swap(SwapStage.READY)
            h.driver.cancel(0)
            assertEquals(0, h.lockCalls)
            h.now = REFUND
            h.claimUntil = REFUND + 500
            h.driver.advance()
            assertEquals(0, h.lockCalls)
            h.now = REFUND + 502
            h.refundUntil = h.now - 1
            h.driver.advance()
            assertEquals(0, h.lockCalls)
            h.now += 601
            h.driver.advance()
            assertEquals(1, h.lockCalls)
        }

    @Test
    fun pendingReadyIsNotResentAfterCancellationOrResignedAfterExpiry() =
        runTest {
            val h = Harness()
            h.prepared()
            h.driver.fund(0)
            h.funded()
            h.spendable = DEPOSIT
            h.driver.ready(0)
            h.now += 121
            h.restart()
            h.driver.advance()
            assertEquals(1, h.readySignatures)
            assertEquals(ReversePhase.REFUND_WAIT, h.record.phase)
            h.driver.cancel(0)
            h.driver.advance()
            assertEquals(1, h.readyCalls)
        }

    @Test
    fun refundPayoutFailureAndRescueCanResumeWithoutFundingAgain() =
        runTest {
            val h = Harness()
            h.prepared()
            h.driver.fund(0)
            h.funded()
            h.escrow = h.swap(SwapStage.REFUNDED)
            h.interruptPayout = true
            assertFailsWith<IllegalStateException> { h.driver.advance() }
            assertNotNull(h.record.payout)
            h.restart()
            h.interruptPayout = false
            h.driver.advance()
            assertEquals(2, h.payoutCalls)
            h.paidOut = true
            h.driver.advance()
            assertEquals(ReversePhase.REFUNDED, h.record.phase)
            for (balance in listOf("0", "100000")) {
                h.vault = balance
                assertFalse(h.driver.canRescue(0))
                h.driver.rescue(0)
                h.driver.rescue(0)
                assertEquals(0, h.rescueCalls)
                assertEquals(0, h.rescueSignatures)
            }
            h.vault = "900000"
            assertTrue(h.driver.canRescue(0))
            h.interruptPayout = true
            assertFailsWith<IllegalStateException> { h.driver.rescue(0) }
            assertTrue(h.record.rescuePending)
            h.restart()
            h.interruptPayout = false
            h.driver.advance()
            assertEquals(2, h.rescueCalls)
            h.driver.rescue(0)
            assertEquals(3, h.rescueCalls)
            assertEquals(1, h.rescueSignatures)
            h.vault = "0"
            h.driver.advance()
            assertFalse(h.record.rescuePending)
            assertEquals(ReversePhase.REFUNDED, h.record.phase)
            assertEquals(1, h.prepares)
        }

    @Test
    fun receiveResumesSameBytesAndRebuildsOnlyAfterProvenExpiry() =
        runTest {
            val h = Harness()
            h.prepared()
            h.funded()
            h.escrow = h.swap(SwapStage.CLAIMED)
            h.driver.advance()
            val original = h.record.receive
            h.restart()
            h.driver.advance()
            assertEquals(original, h.record.receive)
            assertEquals(1, h.receivePrepares)
            h.receiveState = ReverseTransactionStatus.EXPIRED
            h.driver.advance()
            assertEquals(2, h.receivePrepares)
        }

    @Test
    fun unconfirmedEscrowCannotAuthorizeReady() =
        runTest {
            val h = Harness()
            h.prepared()
            h.funded()
            h.spendable = DEPOSIT
            h.block = 10
            assertFailsWith<IllegalStateException> { h.driver.ready(0) }
            assertEquals(0, h.readySignatures)
        }

    @Test
    fun completedReadyDeadlineStartsRecoveryWithoutAnotherAuthorization() =
        runTest {
            val h = Harness()
            h.prepared()
            h.funded()
            h.escrow = h.swap(SwapStage.READY)
            h.now = REFUND
            h.driver.advance()
            assertEquals(1, h.lockCalls)
            assertEquals(0, h.refundCalls)
            assertEquals(0, h.readySignatures)
        }

    @Test
    fun readyRequiresAUsableSweepAndPersistsItsFeeEstimate() =
        runTest {
            val h = Harness()
            h.prepared()
            h.funded()
            h.spendable = DEPOSIT
            h.sweepFee = DEPOSIT
            assertFailsWith<IllegalArgumentException> { h.driver.ready(0) }
            assertEquals(0, h.readySignatures)
            h.sweepFee = 15_000
            h.driver.advance()
            assertEquals(DEPOSIT - 15_000, h.record.receiveEstimate?.receivedZat)
            h.driver.ready(0)
            h.restart()
            assertEquals(15_000, h.record.receiveEstimate?.feeZat)
        }

    @Test
    fun approvalAndOpenUseExactEscrowAmountAndBusinessRoles() =
        runTest {
            val h = Harness()
            h.prepared()
            val calls = ReverseFundingCalls.encode(h.record, ByteArray(65))
            assertEquals(listOf(TOKEN, CONTRACT), calls.map { it.first })
            val approval =
                xyz.justzappit.evm.abi
                    .AbiDecoder(calls[0].second.drop(4).toByteArray())
            assertEquals(Address.parse(CONTRACT), approval.address(0))
            assertEquals(BigInteger(AMOUNT), approval.uint(1))
            val open =
                xyz.justzappit.evm.abi
                    .AbiDecoder(calls[1].second.drop(4).toByteArray())
            assertEquals(Address.parse(MAKER), open.address(0))
            assertEquals(Address.parse(USER), open.address(1))
            assertEquals(Address.parse(TOKEN), open.address(2))
            assertEquals(BigInteger(AMOUNT), open.uint(3))
            assertTrue(open.word(10).contentEquals(fixedHex(NOTE, 32)))
        }

    private class Harness :
        ReverseSwapApi,
        ReverseSwapKeys,
        ReverseSwapChain,
        ReverseSwapFunding,
        ReverseSwapZcash,
        ReverseSwapStore {
        var saved: ReverseSwapRecord? = null
        val record get() = checkNotNull(saved)
        var quote =
            ReverseQuote(
                SwapQuote(
                    hex(32, 1),
                    MAKER,
                    MAKER_SHARE,
                    hex(64, 7),
                    11_155_111,
                    CONTRACT,
                    TOKEN,
                    AMOUNT,
                    DEPOSIT,
                    FUNDING
                ),
                USER,
                NOTE,
                FUNDING,
                READY,
                REFUND
            )
        var block = 15L
        var now = 1_000L
        var nextIndex = 0
        var escrow: OnChainSwap? = null
        var spendable = 0L
        var sweepFee = 10_000L
        var refundUntil = 0L
        var claimUntil = 0L
        var paidOut = false
        var prepares = 0
        var readyCalls = 0
        var readySignatures = 0
        var lockCalls = 0
        var refundCalls = 0
        var payoutCalls = 0
        var rescueCalls = 0
        var rescueSignatures = 0
        var receivePrepares = 0
        var receiveSubmits = 0
        var receiveState = ReverseTransactionStatus.PENDING
        var receiveConfirmations = 0L
        var interruptFunding = false
        var interruptPayout = false
        var proofValid = true
        var vault = "900000"
        val submissions = mutableListOf<ReverseFundingTransaction>()
        var driver = driver()

        fun restart() {
            saved = Json.decodeFromString(Json.encodeToString(record))
            driver = driver()
        }

        suspend fun prepared() {
            driver.quote(1_000_000)
            driver.review(record.index)
            driver.advance()
        }

        fun funded() {
            escrow = swap(SwapStage.OPEN)
        }

        fun swap(stage: SwapStage) =
            OnChainSwap(
                Address.parse(USER),
                READY,
                stage,
                paidOut,
                Address.parse(MAKER),
                REFUND,
                Address.parse(TOKEN),
                claimUntil,
                BigInteger(AMOUNT),
                refundUntil,
                fixedHex(USER_SHARE, 64),
                fixedHex(MAKER_SHARE, 64),
                ByteArray(32) { 3 },
                ByteArray(32)
            )

        fun state() = ReverseChainState(escrow?.let { swap(it.stage) }, fixedHex(NOTE, 32), 10, block, now, 600)

        override suspend fun read(id: String) = state()

        override suspend fun fundingStatus(txId: String) = ReverseTransactionStatus.UNKNOWN

        override suspend fun vaultBalance(id: String) = vault

        override suspend fun active() = saved

        override suspend fun save(record: ReverseSwapRecord) {
            saved = record
        }

        override suspend fun info() = ReverseMakerInfo(1, MAKER, 11_155_111, CONTRACT, TOKEN, "testnet", true)

        override suspend fun quote(units: Int, user: String, refundNote: String) = quote

        override suspend fun accept(quoteId: String, acceptance: ReverseAcceptance) = record.swapId

        override suspend fun terms() = ReverseRelayerTerms(RELAYER, 11_155_111, CONTRACT, "100000")

        override suspend fun ready(authorization: ReverseAuthorization) {
            assertEquals(record.ready, authorization)
            readyCalls++
        }

        override suspend fun lockRefund(authorization: ReverseAuthorization) {
            assertEquals(record.refundLock, authorization)
            lockCalls++
        }

        override suspend fun refund(request: ReverseRefund) {
            assertEquals(record.payout, request.payout)
            refundCalls++
        }

        override suspend fun payout(request: ReversePayout) {
            payoutCalls++
            check(!interruptPayout)
        }

        override suspend fun rescue(request: ReversePayout) {
            rescueCalls++
            check(!interruptPayout)
        }

        override suspend fun signOpen(record: ReverseSwapRecord) = ByteArray(65)

        override suspend fun signReady(record: ReverseSwapRecord, deadline: Long): ByteArray {
            readySignatures++
            return ByteArray(65)
        }

        override suspend fun signLockRefund(record: ReverseSwapRecord, deadline: Long) = ByteArray(65)

        override suspend fun signPayout(record: ReverseSwapRecord, terms: ReverseRelayerTerms) = ByteArray(65)

        override suspend fun signRescue(record: ReverseSwapRecord, terms: ReverseRelayerTerms): ByteArray {
            rescueSignatures++
            return ByteArray(65)
        }

        override suspend fun cost(escrowAmount: String) = ReverseFundingCost("1002506", "2506", null)

        override suspend fun prepare(record: ReverseSwapRecord, signature: ByteArray): ReverseFundingTransaction {
            assertNotNull(record.account)
            prepares++
            return ReverseFundingTransaction("0x1234", hex(32, 8), cost(AMOUNT))
        }

        override suspend fun submit(transaction: ReverseFundingTransaction) {
            assertEquals(record.funding, transaction)
            submissions.add(transaction)
            check(!interruptFunding)
        }

        override suspend fun height() = 100L

        override suspend fun importAccount(record: ReverseSwapRecord) = "account"

        override suspend fun spendable(record: ReverseSwapRecord) = spendable

        override suspend fun estimateReceive(record: ReverseSwapRecord) = ReverseReceiveEstimate(spendable, sweepFee)

        override suspend fun prepareReceive(
            record: ReverseSwapRecord,
            makerSecret: ByteArray,
        ): ReverseReceiveTransaction {
            receivePrepares++
            return ReverseReceiveTransaction(hex(32, 9).removePrefix("0x"), "abcd", 300, DEPOSIT - 10_000, 10_000)
        }

        override suspend fun submit(transaction: ReverseReceiveTransaction) {
            assertEquals(record.receive, transaction)
            receiveSubmits++
        }

        override suspend fun receiveStatus(record: ReverseSwapRecord) =
            ReverseReceiveStatus(
                receiveState,
                receiveConfirmations
            )

        private fun driver() =
            ReverseSwapDriver(
                DEPLOYMENT,
                this,
                this,
                object : AtomicSwapKeys {
                    override suspend fun userShare(index: Int) = fixedHex(USER_SHARE, 64)

                    override suspend fun authAddress(index: Int) = Address.parse(USER)

                    override suspend fun payoutNote(index: Int) =
                        PayoutNote(
                            ByteArray(32),
                            List(3) { ByteArray(32) },
                            ByteArray(32),
                            fixedHex(NOTE, 32)
                        )

                    override suspend fun accept(
                        index: Int,
                        chainId: Long,
                        contract: Address,
                        quoteId: ByteArray,
                        makerShare: ByteArray,
                        makerProof: ByteArray
                    ): UserAcceptance {
                        check(proofValid)
                        return UserAcceptance(fixedHex(USER_SHARE, 64), ByteArray(64), ByteArray(64))
                    }

                    override suspend fun depositAddress(index: Int, makerShare: ByteArray) = "utest"

                    override suspend fun claimSecret(index: Int) = ByteArray(32)

                    override suspend fun signLockClaim(
                        index: Int,
                        chainId: Long,
                        contract: Address,
                        swapId: ByteArray,
                        deadline: Long,
                    ) =
                        error(
                            "forward only"
                        )

                    override suspend fun signPayout(
                        index: Int,
                        chainId: Long,
                        contract: Address,
                        swapId: ByteArray,
                        relayer: Address,
                        fee: BigInteger
                    ) =
                        error(
                            "forward only"
                        )
                },
                this,
                this,
                this,
                object : AtomicSwapStore {
                    override suspend fun takeIndex() = nextIndex++

                    override suspend fun active(): AtomicSwapRecord? = null

                    override suspend fun save(record: AtomicSwapRecord) = error("forward only")
                },
                this
            )
    }

    private companion object {
        const val AMOUNT = "1000000"
        const val DEPOSIT = 100_000L
        const val FUNDING = 2_000L
        const val READY = 4_000L
        const val REFUND = 6_000L
        val USER = hex(20, 1)
        val MAKER = hex(20, 2)
        val TOKEN = hex(20, 3)
        val CONTRACT = hex(20, 4)
        val RELAYER = hex(20, 5)
        val NOTE = hex(32, 6)
        val MAKER_SHARE = hex(64, 2)
        val USER_SHARE = hex(64, 1)
        val DEPLOYMENT =
            ReverseDeployment(
                "https://maker",
                "https://relayer",
                "https://rpc",
                11_155_111,
                CONTRACT,
                TOKEN,
                hex(20, 6),
                MAKER,
                RELAYER,
                "100000"
            )

        fun hex(bytes: Int, value: Int) = "0x" + value.toString(16).padStart(2, '0').repeat(bytes)
    }
}
