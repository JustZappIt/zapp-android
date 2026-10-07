// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.atomicswap

import io.ktor.http.Url
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import xyz.justzappit.evm.rpc.TransactionStatus
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.types.ChainId
import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.offramp.p2p.Usdc6
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

abstract class ReverseSwapDriverFixtures {
    protected class Harness :
        SwapMaker,
        SwapRelayer,
        ReverseSwapKeys,
        ReverseSwapChain,
        ReverseSwapFunding,
        ReverseSwapZcash,
        ReverseSwapStore {
        var saved: ReverseSwapRecord? = null
        private val earlier = mutableMapOf<Int, ReverseSwapRecord>()
        val record get() = checkNotNull(saved)
        var quote =
            ReverseQuote(
                SwapQuote(
                    hex(32, 1),
                    MAKER,
                    MAKER_SHARE,
                    hex(64, 7),
                    ChainId(11_155_111),
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
        var info = MakerInfo(1, MAKER, ChainId(11_155_111), CONTRACT, TOKEN, SwapZcashNetwork.TESTNET, true)
        var relayer = RELAYER
        var block = 15L
        var now = 1_000L
        var nextIndex = 0
        var escrow: OnChainSwap? = null
        var paidIn = 0L
        var depositConfirmations = 10
        var sweepFee = 10_000L
        var beforePayout: () -> Unit = {}
        var beforeSecret: () -> Unit = {}
        var beforeUpdate: () -> Unit = {}
        var rescueNonce: Long? = 0L
        var refundUntil = 0L
        var claimUntil = 0L
        var paidOut = false
        var accepts = 0
        var prepares = 0
        var saves = 0
        var readyCalls = 0
        var readySignatures = 0
        var lockCalls = 0
        var refundCalls = 0
        var payoutCalls = 0
        var rescueCalls = 0
        var rescueSignatures = 0
        var receivePrepares = 0
        var receiveSubmits = 0
        var received: ZcashTransactionStatus = ZcashTransactionStatus.Unmined
        var fundingState = TransactionStatus.UNKNOWN
        var clock: Long? = null
        var forgets = 0
        var payoutSignatures = 0
        var interruptFunding = false
        var sponsoredFunding = false
        var submittedFundingHash: TxHash? = null
        var submitting: CompletableDeferred<TxHash?>? = null
        var interruptPayout = false
        var interruptReceive = false
        var proofValid = true
        var proving: CompletableDeferred<Unit>? = null
        var vault = Usdc6.ofMicros(900_000)
        var refundNote = NOTE
        val submissions = mutableListOf<ReverseFundingTransaction>()

        /** Whose Railgun wallet each note, acceptance and signature over a note was for. */
        val railgunKeys = mutableListOf<RailgunKeySource>()

        fun restart() {
            saved = Json.decodeFromString(Json.encodeToString(record))
            driver = driver()
        }

        suspend fun prepared() {
            driver.quote(AMOUNT)
            driver.goAhead(record.index)
            driver.advance()
        }

        fun funded() {
            escrow = swap(SwapStage.OPEN)
        }

        fun kept(index: Int) = checkNotNull(saved?.takeIf { it.index == index } ?: earlier[index])

        fun swap(stage: SwapStage) =
            OnChainSwap(
                USER,
                READY,
                stage,
                paidOut,
                MAKER,
                REFUND,
                TOKEN,
                claimUntil,
                AMOUNT,
                refundUntil,
                USER_SHARE,
                MAKER_SHARE,
                ByteArray(32) { 3 },
                EMPTY_NOTE,
            )

        fun state() = ReverseChainState(escrow?.let { swap(it.stage) }, refundNote, 10, block, now, 600)

        override suspend fun read(
            id: SwapId,
            terms: SwapTerms
        ) = state().let { it.copy(swap = it.swap?.state()?.verified(terms)) }

        override suspend fun fundingStatus(transaction: TxHash) = fundingState

        override suspend fun rescueNonce(id: SwapId) = rescueNonce

        override suspend fun vaultBalance(id: SwapId) = vault

        override suspend fun active() = saved

        override suspend fun find(index: Int) = saved?.takeIf { it.index == index } ?: earlier[index]

        override suspend fun save(record: ReverseSwapRecord) {
            beforeUpdate()
            saved?.let { earlier[it.index] = it }
            saved = record
            saves++
        }

        override suspend fun update(record: ReverseSwapRecord) {
            beforeUpdate()
            if (saved?.index == record.index) saved = record else earlier[record.index] = record
        }

        override suspend fun info() = info

        override suspend fun quoteReverse(
            amount: Usdc6,
            user: Address,
            refundNote: NoteCommitment,
        ) = quote

        override suspend fun acceptReverse(
            quoteId: String,
            swapId: SwapId,
            acceptance: SwapAcceptance,
        ): SwapAccepted {
            accepts++
            return SwapAccepted(record.swapId, record.quote.readyDeadline, record.quote.refundAfter)
        }

        override suspend fun collectReverseToken(swapId: SwapId) = Unit

        override suspend fun quote(
            amount: Usdc6,
            payout: Address,
            payoutNote: NoteCommitment,
        ) = error("forward only")

        override suspend fun accept(
            quoteId: String,
            swapId: SwapId,
            acceptance: SwapAcceptance,
        ) = error("forward only")

        override suspend fun collectToken(swapId: SwapId) = error("forward only")

        override suspend fun terms() = RelayerTerms(relayer, ChainId(11_155_111), CONTRACT, Usdc6.ofMicros(100_000))

        override suspend fun ready(
            authorization: SwapAuthorization,
            terms: SwapTerms
        ): Sent {
            assertEquals(record.ready, authorization)
            readyCalls++
            return Sent(emptyList())
        }

        override suspend fun lockRefund(
            authorization: SwapAuthorization,
            terms: SwapTerms
        ): Sent {
            assertEquals(record.refundLock, authorization)
            lockCalls++
            return Sent(emptyList())
        }

        override suspend fun refund(
            reveal: SwapReveal,
            terms: SwapTerms
        ): Sent {
            assertEquals(record.payout, reveal.payout)
            refundCalls++
            return Sent(emptyList())
        }

        override suspend fun refundPayout(
            payout: SwapPayout,
            terms: SwapTerms
        ): Sent {
            payoutCalls++
            check(!interruptPayout)
            return Sent(emptyList())
        }

        override suspend fun rescue(
            rescue: SwapRescue,
            terms: SwapTerms
        ): Sent {
            rescueCalls++
            check(!interruptPayout)
            return Sent(emptyList())
        }

        override suspend fun lockClaim(
            authorization: SwapAuthorization,
            terms: SwapTerms
        ) = error("forward only")

        override suspend fun claim(
            reveal: SwapReveal,
            terms: SwapTerms
        ) = error("forward only")

        override suspend fun payout(
            payout: SwapPayout,
            terms: SwapTerms
        ) = error("forward only")

        override suspend fun signOpen(record: ReverseSwapRecord): ByteArray {
            railgunKeys += record.railgunKeys
            return ByteArray(65)
        }

        override suspend fun signReady(record: ReverseSwapRecord, deadline: Long): ByteArray {
            readySignatures++
            return ByteArray(65)
        }

        override suspend fun signLockRefund(record: ReverseSwapRecord, deadline: Long) = ByteArray(65)

        override suspend fun signPayout(record: ReverseSwapRecord, terms: RelayerTerms): ByteArray {
            beforePayout()
            payoutSignatures++
            return ByteArray(65)
        }

        override suspend fun signRescue(
            record: ReverseSwapRecord,
            terms: RelayerTerms,
            nonce: Long,
            deadline: Long,
        ): ByteArray {
            rescueSignatures++
            railgunKeys += record.railgunKeys
            return ByteArray(65)
        }

        override suspend fun fee() = Usdc6.ofMicros(250_000)

        override suspend fun cost(escrow: Usdc6) =
            ReverseFundingCost(Usdc6.ofMicros(1_252_506), Usdc6.ofMicros(2_506), fee())

        override suspend fun prepare(record: ReverseSwapRecord, signature: ByteArray): ReverseFundingTransaction {
            assertNotNull(record.account)
            prepares++
            proving?.await()
            if (sponsoredFunding) {
                return ReverseFundingTransaction(
                    cost = cost(AMOUNT),
                    request =
                        ReverseFundingRequest(record.swapId, record.deployment.chainId, CONTRACT, "0x12345678", "0"),
                )
            }
            return ReverseFundingTransaction("0x1234", TxHash.fromHex(hex(32, 8)), cost(AMOUNT))
        }

        override suspend fun submit(transaction: ReverseFundingTransaction): TxHash? {
            assertEquals(record.funding, transaction)
            submissions.add(transaction)
            check(!interruptFunding)
            submitting?.let { return it.await() }
            return submittedFundingHash ?: transaction.txId
        }

        override suspend fun fundReverse(request: ReverseFundingRequest) = Sent(emptyList())

        override suspend fun chainHeight() = 100L

        override suspend fun importAccount(record: ReverseSwapRecord) = ACCOUNT

        override suspend fun estimateReceive(
            record: ReverseSwapRecord,
            confirmations: Int,
        ) = ReverseReceiveEstimate(if (depositConfirmations >= confirmations) paidIn else 0, sweepFee)

        override suspend fun prepareReceive(
            record: ReverseSwapRecord,
            makerSecret: ByteArray,
        ): ReverseReceiveTransaction {
            receivePrepares++
            check(!interruptReceive) { "Zcash transfer preparation unavailable" }
            return ReverseReceiveTransaction(ZcashTxId.parse("09".repeat(32)), "abcd", 300, DEPOSIT - 10_000, 10_000)
        }

        override suspend fun submit(transaction: ZcashTransaction) {
            assertEquals(record.receive?.transaction, transaction)
            receiveSubmits++
        }

        override suspend fun receiveStatus(record: ReverseSwapRecord, receive: ReverseReceiveTransaction) = received

        override suspend fun forget(record: ReverseSwapRecord) {
            forgets++
        }

        private val keys =
            object : AtomicSwapKeys {
                override suspend fun userShare(index: Int) = USER_SHARE

                override suspend fun authAddress(index: Int) = USER

                override suspend fun payoutNote(
                    index: Int,
                    railgunKeys: RailgunKeySource
                ): PayoutNote {
                    this@Harness.railgunKeys += railgunKeys
                    val note = if (railgunKeys == RailgunKeySource.BIP85) NOTE else LEGACY_NOTE
                    return PayoutNote(note.bytes, List(3) { ByteArray(32) }, ByteArray(32), note)
                }

                override suspend fun accept(
                    index: Int,
                    railgunKeys: RailgunKeySource,
                    chainId: ChainId,
                    contract: Address,
                    quoteId: ByteArray,
                    makerShare: SwapShare,
                    makerProof: ByteArray
                ): UserAcceptance {
                    check(proofValid)
                    this@Harness.railgunKeys += railgunKeys
                    return UserAcceptance(USER_SHARE, ByteArray(64), ByteArray(64))
                }

                override suspend fun depositAddress(index: Int, makerShare: SwapShare) = "utest"

                override suspend fun claimSecret(index: Int) = ByteArray(32).also { beforeSecret() }

                override suspend fun signLockClaim(
                    index: Int,
                    chainId: ChainId,
                    contract: Address,
                    swapId: SwapId,
                    deadline: Long,
                ) = error("forward only")

                override suspend fun signPayout(
                    index: Int,
                    chainId: ChainId,
                    contract: Address,
                    swapId: SwapId,
                    relayer: Address,
                    fee: Usdc6
                ) = error("forward only")
            }

        private val forward =
            object : AtomicSwapStore {
                override suspend fun takeIndex() = nextIndex++

                override suspend fun active(): AtomicSwapRecord? = null

                override suspend fun save(record: AtomicSwapRecord) = error("forward only")
            }

        var driver = driver()

        private fun driver() =
            ReverseSwapDriver(
                deployment = DEPLOYMENT,
                maker = this,
                relayer = this,
                chain = this,
                keys = keys,
                reverseKeys = this,
                zcash = this,
                funding = this,
                indices = SwapIndices(forward, keys, emptyList()),
                forward = forward,
                store = this,
                nowSeconds = { clock ?: now },
            )
    }

    protected companion object {
        const val DEPOSIT = 100_000L
        const val FUNDING = 2_000L
        const val READY = 4_000L
        const val REFUND = 6_000L
        val AMOUNT = Usdc6.ofMicros(1_000_000)
        val USER = address(1)
        val MAKER = address(2)
        val TOKEN = address(3)
        val CONTRACT = address(4)
        val RELAYER = address(5)
        val NOTE = NoteCommitment.parse(hex(32, 6))
        val LEGACY_NOTE = NoteCommitment.parse(hex(32, 9))
        val EMPTY_NOTE = NoteCommitment.of(ByteArray(32))
        val MAKER_SHARE = SwapShare.parse(hex(64, 2))
        val USER_SHARE = SwapShare.parse(hex(64, 1))
        val ACCOUNT = JointAccountId.parse("0a".repeat(16))
        val DEPLOYMENT =
            SwapDeployment(
                Url("https://maker"),
                Url("https://relayer"),
                Url("https://rpc"),
                ChainId(11_155_111),
                CONTRACT,
                TOKEN,
                address(6),
                MAKER,
                RELAYER,
                Usdc6.ofMicros(100_000),
            )

        fun hex(bytes: Int, value: Int) = "0x" + value.toString(16).padStart(2, '0').repeat(bytes)

        fun address(value: Int) = Address.parse(hex(20, value))
    }
}
