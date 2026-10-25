// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.atomicswap

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.io.IOException
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.types.ChainId
import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.evm.util.toHex
import xyz.justzappit.offramp.p2p.Usdc6

abstract class AtomicSwapDriverFixtures {
    protected fun confirming() = waiting(AtomicSwapWait.CONFIRMING)

    protected fun waiting(reason: AtomicSwapWait) =
        AtomicSwapStep.Waiting(reason, NOW + 35 * 60, NOW + 35 * 60 + T0_TO_T1)

    protected class Harness(
        val deployment: SwapDeployment = DEPLOYMENT,
    ) {
        val chain = FakeChain()
        val zcash = FakeZcash()
        val store = FakeStore()
        val paths = mutableListOf<String>()
        val bodies = mutableMapOf<String, String>()
        var clock = NOW
        var quoteJson = QUOTE_JSON
        var acceptStatus = HttpStatusCode.OK
        var acceptCode: String? = null
        var acceptedSwapId = SWAP_ID.hex

        /** The ticket the maker answers the accept with. */
        var acceptTicket: String? = TICKET

        /** The late window the maker answers the accept with, none where an older maker says nothing of it. */
        var acceptLateWindow: Long? = LATE_WINDOW

        /** What the maker's info and the relayer's terms say of the contract: its kind and windows, or nothing. */
        var contractJson = ""","contractKind":"windows","minWindow":$MIN_WINDOW,"lateWindow":$LATE_WINDOW"""

        /** The `ZecSwap-Ticket` header each path last went out with, none where it carried none. */
        val tickets = mutableMapOf<String, String?>()
        var relayer = RELAYER
        var relayerFee = "20000"
        var feeExpiresAt: Long? = null

        /** The least fee the relayer takes: a claim or payout signed for less is refused, and nothing is sent. */
        var leastFee: Long? = null
        var feeAfterClaim: String? = null
        var claimPaysOut = true
        var zcashNetwork = "testnet"
        var unreachable: String? = null

        /** The swap's record as the store keeps it when each claim reaches the relayer, refused or not. */
        val keptAtClaim = mutableListOf<AtomicSwapRecord?>()

        /** How the relayer answers a claim handed over to hold: by default, as one without the route does. */
        var holdStatus = HttpStatusCode.NotFound
        var holdCode: String? = null

        /** The swap's record as the store keeps it when each claim handed over reaches the relayer. */
        val keptAtHold = mutableListOf<AtomicSwapRecord?>()
        private val engine =
            MockEngine { request ->
                val path = request.url.encodedPath
                paths += path
                tickets[path] = request.headers["ZecSwap-Ticket"]
                val sent = (request.body as? TextContent)?.text.orEmpty()
                bodies[path] = sent
                if (path == unreachable) throw IOException("connection refused")
                if (path == "/v1/claim") keptAtClaim += store.record
                if (path == HOLD) keptAtHold += store.record
                val fee = FEE_FIELD.find(sent)?.let { it.groupValues[1].toLong() }
                val refused = fee != null && leastFee?.let { fee < it } == true
                val status =
                    when {
                        path.endsWith("/accept") -> acceptStatus
                        refused -> HttpStatusCode.BadRequest
                        path == HOLD -> holdStatus
                        else -> HttpStatusCode.OK
                    }
                val body =
                    when {
                        status != HttpStatusCode.OK -> {
                            val named =
                                when {
                                    refused -> "rejected"
                                    path == HOLD -> holdCode
                                    else -> acceptCode
                                }
                            val code = named?.let { "\"code\":\"$it\"," }.orEmpty()
                            """{$code"error":"${status.description}"}"""
                        }

                        path == "/v1/info" -> {
                            """{"apiVersion":1,"maker":"${MAKER.lowercaseHex}","chainId":11155111,""" +
                                """"contract":"${CONTRACT.lowercaseHex}","token":"${TOKEN.lowercaseHex}",""" +
                                """"zcashNetwork":"$zcashNetwork","reverseEnabled":false$contractJson}"""
                        }

                        path == "/v1/quote" -> {
                            quoteJson
                        }

                        path.endsWith("/accept") -> {
                            chain.opened = true
                            val ticket = acceptTicket?.let { ",\"ticket\":\"$it\"" }.orEmpty()
                            val late = acceptLateWindow?.let { ",\"lateWindow\":$it" }.orEmpty()
                            """{"swapId":"$acceptedSwapId","t0":${chain.t0},"t1":${chain.t1}$ticket$late}"""
                        }

                        path == "/v1/terms" -> {
                            val expiry = feeExpiresAt?.let { ",\"feeExpiresAt\":$it" }.orEmpty()
                            """{"relayer":"${relayer.lowercaseHex}","chainId":11155111,""" +
                                """"contract":"${CONTRACT.lowercaseHex}","fee":"$relayerFee"$expiry$contractJson}"""
                        }

                        path == "/v1/claim" -> {
                            chain.stage = SwapStage.CLAIMED
                            chain.paidOut = claimPaysOut
                            chain.payoutHash = TxHash.fromHex(hash(1))
                            chain.payoutFee = Usdc6.ofMicros(checkNotNull(fee))
                            feeAfterClaim?.let { relayerFee = it }
                            sent(if (claimPaysOut) 2 else 1)
                        }

                        path == HOLD -> {
                            if (chain.stage == SwapStage.CLAIMED) sent(2) else """{"held":true}"""
                        }

                        path == "/v1/payout" -> {
                            sent(1).also {
                                chain.paidOut = true
                                chain.payoutHash = TxHash.fromHex(hash(0))
                                chain.payoutFee = Usdc6.ofMicros(checkNotNull(fee))
                            }
                        }

                        else -> {
                            error("unexpected $path")
                        }
                    }
                respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        private val http = HttpClient(engine)
        val keys = FakeKeys()
        val driver =
            AtomicSwapDriver(
                deployment = deployment,
                depositTerms = ZcashDepositTerms(),
                maker = MakerClient(http, Url("http://maker")),
                relayer = RelayerClient(http, Url("http://relayer")),
                chain = chain,
                keys = keys,
                zcash = zcash,
                store = store,
                indices = SwapIndices(store, keys, listOf(SwapContract(chain, MAKER))),
                nowSeconds = { clock },
            )

        suspend fun accepted(): AtomicSwapRecord = driver.accept(driver.quote(ONE_UNIT))

        suspend fun depositedRecord(): AtomicSwapRecord {
            driver.advance(accepted())
            return store.record!!
        }

        suspend fun minedDeposit(): AtomicSwapRecord =
            depositedRecord().also { zcash.status[txId("deposit1")] = ZcashTransactionStatus.Mined(5) }

        /** The chain's newest block and this device's clock, both at [time]. */
        fun at(time: Long) {
            chain.now = time
            clock = time
        }

        private fun sent(transactions: Int): String {
            val hashes = List(transactions) { "\"${hash(it)}\"" }.joinToString()
            return """{"transactions":[$hashes]}"""
        }
    }

    protected class FakeChain : AtomicSwapChainReader {
        var opened = false

        /** The newest block's time and number. */
        var now = NOW
        var head = HEAD
        var stage = SwapStage.OPEN
        var t0 = NOW + 35 * 60
        var t1Offset = T0_TO_T1
        val t1 get() = t0 + t1Offset
        var paidOut = false
        var makerShare = MAKER_SHARE
        var payoutNote = NOTE_COMMITMENT
        var secret = ByteArray(32)
        var railgunAccepts = true
        var confirmed: (() -> SwapState?)? = null

        /** What the newest block shows, where it shows something other than the confirmed read. */
        var latest: (() -> SwapState?)? = null

        /** The block the swap's ready landed in: below it, the swap reads as open still. */
        var readyBlock = 0L

        /** The blocks the swap was read at, below the newest. */
        val readAt = mutableListOf<Long>()

        /** Consistent reads made, each judging a reveal. */
        var reads = 0

        /** When the swap's refund was mined, as its confirmed event tells it. */
        var refundedAt: Long? = null

        /** What the swap on chain opened with, from what the maker answered the accept with. */
        var tamper: (SwapTerms) -> SwapTerms = { it }
        var payoutVisible = true
        var payoutHash = TxHash.fromHex(hash(0))
        var payoutFee = Usdc6.ofMicros(20_000)

        override suspend fun confirmedSwap(
            id: SwapId,
            terms: SwapTerms
        ): OnChainSwap? {
            val state = confirmed.let { if (it != null) it() else swap(id) }
            return state?.verified(terms)
        }

        override suspend fun swapNow(
            id: SwapId,
            terms: SwapTerms
        ): SwapRead {
            reads++
            val state = latest.let { if (it != null) it() else swap(id) }
            return SwapRead(state?.verified(terms), ChainHead(head, now))
        }

        override suspend fun swapAt(
            id: SwapId,
            terms: SwapTerms,
            block: Long,
        ): OnChainSwap? {
            readAt += block
            val then = if (stage == SwapStage.READY && block < readyBlock) SwapStage.OPEN else stage
            return onChain(id)?.let { it.atStage(then).state(tamper(it.terms)) }?.verified(terms)
        }

        override suspend fun confirmedPayout(
            id: SwapId,
            since: Long
        ): SwapPayoutEvidence? =
            SwapPayoutEvidence(payoutHash, RELAYER, payoutFee).takeIf { paidOut && payoutVisible && id == SWAP_ID }

        override suspend fun refundedAt(
            id: SwapId,
            since: Long
        ): Long? = refundedAt.takeIf { id == SWAP_ID }

        override suspend fun swap(id: SwapId): SwapState? = onChain(id)?.let { it.state(tamper(it.terms)) }

        /** The swap as the chain keeps it now, as every read shows it but where [confirmed] or [latest] say else. */
        fun current(): SwapState? = onChain(SWAP_ID)?.let { it.state(tamper(it.terms)) }

        private fun onChain(id: SwapId): OnChainSwap? =
            OnChainSwap(
                maker = MAKER,
                t0 = t0,
                stage = stage,
                paidOut = paidOut,
                user = AUTH,
                t1 = t1,
                token = TOKEN,
                amount = AMOUNT,
                makerShare = makerShare,
                userShare = USER_SHARE,
                secret = secret,
                payoutNote = payoutNote,
            ).takeIf { opened && id == SWAP_ID }

        override suspend fun now() = now

        override suspend fun railgunAccepts(token: Address) = railgunAccepts

        override suspend fun makerKeyUsed(
            owner: Address,
            share: SwapShare
        ) = false

        override suspend fun payoutTx(
            id: SwapId,
            near: Long
        ): TxHash? = null
    }

    protected class FakeKeys : AtomicSwapKeys {
        val signedFees = mutableListOf<Usdc6>()
        var beforeClaimSecret: () -> Unit = {}

        override suspend fun userShare(index: Int) = USER_SHARE

        override suspend fun authAddress(index: Int) = AUTH

        override suspend fun payoutNote(index: Int) = NOTE

        override suspend fun accept(
            index: Int,
            chainId: ChainId,
            contract: Address,
            quoteId: ByteArray,
            makerShare: SwapShare,
            makerProof: ByteArray,
        ) = UserAcceptance(USER_SHARE, ByteArray(64) { 4 }, ByteArray(64) { 5 })

        override suspend fun depositAddress(
            index: Int,
            makerShare: SwapShare
        ) = "utest1deposit"

        override suspend fun claimSecret(index: Int) = ByteArray(32) { 9 }.also { beforeClaimSecret() }

        override suspend fun signPayout(
            index: Int,
            chainId: ChainId,
            contract: Address,
            swapId: SwapId,
            relayer: Address,
            fee: Usdc6,
        ) = ByteArray(65) { 8 }.also { signedFees += fee }
    }

    protected class FakeZcash : AtomicSwapZcash {
        val deposits = mutableListOf<Pair<String, Long>>()
        val sweeps = mutableListOf<Triple<Int, SwapShare, Long>>()
        private val sent = mutableListOf<ZcashTransaction>()
        val status = mutableMapOf<ZcashTxId, ZcashTransactionStatus>()

        /** The deposit the wallet created last, as its history shows it. */
        var created: ZcashTransaction? = null
        var unpayable = false
        var spendable = true
        var forgotten = 0

        fun submitted() = sent.map { it.txId }

        override suspend fun chainHeight() = 4_200_000L

        override suspend fun prepareDeposit(
            address: String,
            zatoshi: Long,
            maxTotalZat: Long?,
        ): PreparedDeposit {
            if (unpayable) throw AtomicSwapBlockedException(AtomicSwapBlock.DEPOSIT_UNPAYABLE, "short")
            return PreparedDeposit {
                deposits += address to zatoshi
                transaction("deposit${deposits.size}").also { created = it }
            }
        }

        override suspend fun findDeposit(address: String) =
            created?.takeUnless { status[it.txId] == ZcashTransactionStatus.Expired }

        override suspend fun depositStatus(deposit: ZcashTransaction) =
            status[deposit.txId] ?: ZcashTransactionStatus.Unmined

        override suspend fun prepareSweep(
            index: Int,
            makerShare: SwapShare,
            makerSecret: ByteArray,
            birthday: Long,
        ): ZcashTransaction? {
            if (!spendable) return null
            sweeps += Triple(index, makerShare, birthday)
            return transaction("sweep${sweeps.size}")
        }

        override suspend fun sweepStatus(
            index: Int,
            makerShare: SwapShare,
            sweep: ZcashTransaction,
        ) = status[sweep.txId] ?: ZcashTransactionStatus.Unmined

        override suspend fun forgetDepositAccount(
            index: Int,
            makerShare: SwapShare
        ) {
            forgotten++
        }

        override suspend fun submit(transaction: ZcashTransaction) {
            sent += transaction
        }
    }

    protected class FakeStore : AtomicSwapStore {
        var record: AtomicSwapRecord? = null
        private var next = 0

        val depositTxId: ZcashTxId? get() = record?.deposit?.txId

        override suspend fun takeIndex() = next++

        override suspend fun active() = record

        override suspend fun save(record: AtomicSwapRecord) {
            this.record = record
        }
    }

    protected companion object {
        const val NOW = 1_790_000_000L
        const val HEAD = 9_000_000L
        const val MIN_WINDOW = 1_800L
        const val LATE_WINDOW = 3_600L
        const val T0_TO_T1 = 3_600L
        const val FEE_VALIDITY = 3_600L
        const val DEPOSIT_ZAT = 202_021L
        const val QUOTE_ID = "0x" + "22222222222222222222222222222222" + "22222222222222222222222222222222"
        const val HOLD = "/v1/claim/hold"
        val MAKER = Address.parse("0x09eD1F966745Be18C711C346242c0974DAd7c3e5")
        val RELAYER = Address.parse("0x507d1d152025e9f6da7bc03b358acc247f07b4eb")
        val AUTH = Address.parse("0x4444444444444444444444444444444444444444")
        val CONTRACT = Address.parse("0x32CE55D00E6184c385E44e6b20b76d3a8407E809")
        val TOKEN = Address.parse("0x5764D0044bef5AA839E0dDafE2073421101B9Ed8")
        val AMOUNT = Usdc6.ofMicros(1_000_000)
        val USER_SHARE = SwapShare.of(filled(64, 0x0c))
        val MAKER_SHARE = SwapShare.of(filled(64, 0x0a))
        val NOTE_COMMITMENT = NoteCommitment.of(filled(32, 0x05))
        val NOTE = note(npk = 1, commitment = NOTE_COMMITMENT)
        val ONE_UNIT = Usdc6.ofMicros(1)
        val FEE_FIELD = Regex(""""fee":"(\d+)"""")
        val SWAP_ID = SwapId.of(MAKER, USER_SHARE)

        /** The maker's ticket for [SWAP_ID]: its id, a dot, and a MAC the app never reads. */
        val TICKET = "${SWAP_ID.hex}." + "3f".repeat(32)
        val DEPLOYMENT =
            SwapDeployment(
                makerUrl = Url("http://maker"),
                relayerUrl = Url("http://relayer"),
                rpcUrl = Url("http://rpc"),
                chainId = ChainId(11_155_111),
                contract = CONTRACT,
                token = TOKEN,
                railgunProxy = Address.parse("0xeCFCf3b4eC647c4Ca6D49108b311b7a7C9543fea"),
                maker = MAKER,
                relayer = RELAYER,
                windows = SwapWindows(MIN_WINDOW, LATE_WINDOW),
            )
        val QUOTE_JSON =
            """
            {"quoteId":"$QUOTE_ID","maker":"${MAKER.checksumHex}","makerShare":"${MAKER_SHARE.hex}",
             "makerProof":"${filled(64, 6).hex()}","chainId":11155111,"contract":"${CONTRACT.checksumHex}",
             "token":"${TOKEN.checksumHex}","amount":"1000000","depositZat":$DEPOSIT_ZAT,"expiresAt":${NOW + 300}}
            """.trimIndent()

        fun hash(value: Int) = "0x" + value.toString(16).padStart(64, '0')

        // Not `ByteArray(size) { value }`: its inlined loop in this class's static initializer fails verification
        // once coverage instruments it.
        fun filled(
            size: Int,
            value: Byte
        ) = ByteArray(size).apply { fill(value) }

        /** A transaction id named for what it is, so a test reads which one it means. */
        fun txId(name: String) = ZcashTxId.parse(name.encodeToByteArray().toHex().padEnd(64, '0'))

        fun note(
            npk: Byte,
            commitment: NoteCommitment
        ) = PayoutNote(ByteArray(32) { npk }, List(3) { ByteArray(32) { 2 } }, ByteArray(32) { 3 }, commitment)

        fun transaction(name: String) = ZcashTransaction(txId(name), "00", 4_200_040)
    }
}

/** What the contract keeps of [this] swap, opened with [terms]. */
internal fun OnChainSwap.state(terms: SwapTerms = this.terms) = SwapState(terms.hash(), stage, paidOut, secret)

/** [this] swap at [stage]. */
internal fun OnChainSwap.atStage(stage: SwapStage) =
    OnChainSwap(maker, t0, stage, paidOut, user, t1, token, amount, makerShare, userShare, secret, payoutNote)
