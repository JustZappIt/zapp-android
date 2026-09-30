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
    protected fun confirming() = AtomicSwapStep.Waiting(AtomicSwapWait.CONFIRMING, NOW + 35 * 60, NOW + 35 * 60 + 300)

    protected class Harness {
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
        var relayer = RELAYER
        var relayerFee = "20000"
        var feeAfterClaim: String? = null
        var claimPaysOut = true
        var zcashNetwork = "testnet"
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
                            val code = acceptCode?.let { "\"code\":\"$it\"," }.orEmpty()
                            """{$code"error":"${status.description}"}"""
                        }

                        path == "/v1/info" -> {
                            """{"apiVersion":1,"maker":"${MAKER.lowercaseHex}","chainId":11155111,""" +
                                """"contract":"${CONTRACT.lowercaseHex}","token":"${TOKEN.lowercaseHex}",""" +
                                """"zcashNetwork":"$zcashNetwork","reverseEnabled":false}"""
                        }

                        path == "/v1/quote" -> {
                            quoteJson
                        }

                        path.endsWith("/accept") -> {
                            chain.opened = true
                            """{"swapId":"$acceptedSwapId"}"""
                        }

                        path == "/v1/terms" -> {
                            """{"relayer":"${relayer.lowercaseHex}","chainId":11155111,""" +
                                """"contract":"${CONTRACT.lowercaseHex}","fee":"$relayerFee"}"""
                        }

                        path == "/v1/lock-claim" -> {
                            sent(1).also { chain.claimLockUntil = chain.now + LOCK_SECONDS }
                        }

                        path == "/v1/claim" -> {
                            chain.stage = SwapStage.CLAIMED
                            chain.paidOut = claimPaysOut
                            feeAfterClaim?.let { relayerFee = it }
                            sent(if (claimPaysOut) 2 else 1)
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
        val keys = FakeKeys()
        val driver =
            AtomicSwapDriver(
                deployment = DEPLOYMENT,
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

        private fun sent(transactions: Int): String {
            val hashes = List(transactions) { "\"${hash(it)}\"" }.joinToString()
            return """{"transactions":[$hashes]}"""
        }
    }

    protected class FakeChain : AtomicSwapChainReader {
        var opened = false
        var now = NOW
        var stage = SwapStage.OPEN
        var t0 = NOW + 35 * 60
        var claimLockUntil = 0L
        var refundLockUntil = 0L
        var paidOut = false
        var makerShare = MAKER_SHARE
        var payoutNote = NOTE_COMMITMENT
        var secret = ByteArray(32)
        var railgunAccepts = true

        override suspend fun swap(id: SwapId): OnChainSwap? =
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
                makerShare = makerShare,
                userShare = USER_SHARE,
                secret = secret,
                payoutNote = payoutNote,
            ).takeIf { opened && id == SWAP_ID }

        override suspend fun now() = now

        override suspend fun railgunAccepts(token: Address) = railgunAccepts

        override suspend fun lockDuration() = LOCK_SECONDS.toLong()

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
        /** Whose Railgun wallet each note and acceptance was for, in order. */
        val railgunKeys = mutableListOf<RailgunKeySource>()

        override suspend fun userShare(index: Int) = USER_SHARE

        override suspend fun authAddress(index: Int) = AUTH

        override suspend fun payoutNote(
            index: Int,
            railgunKeys: RailgunKeySource
        ): PayoutNote {
            this.railgunKeys += railgunKeys
            return notes.getValue(railgunKeys)
        }

        override suspend fun accept(
            index: Int,
            railgunKeys: RailgunKeySource,
            chainId: ChainId,
            contract: Address,
            quoteId: ByteArray,
            makerShare: SwapShare,
            makerProof: ByteArray,
        ): UserAcceptance {
            this.railgunKeys += railgunKeys
            return UserAcceptance(USER_SHARE, ByteArray(64) { 4 }, ByteArray(64) { 5 })
        }

        override suspend fun depositAddress(
            index: Int,
            makerShare: SwapShare
        ) = "utest1deposit"

        override suspend fun claimSecret(index: Int) = ByteArray(32) { 9 }

        override suspend fun signLockClaim(
            index: Int,
            chainId: ChainId,
            contract: Address,
            swapId: SwapId,
            deadline: Long,
        ) = ByteArray(65) { 7 }

        override suspend fun signPayout(
            index: Int,
            chainId: ChainId,
            contract: Address,
            swapId: SwapId,
            relayer: Address,
            fee: Usdc6,
        ) = ByteArray(65) { 8 }
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
        const val LOCK_SECONDS = 600
        const val DEPOSIT_ZAT = 202_021L
        const val QUOTE_ID = "0x" + "22222222222222222222222222222222" + "22222222222222222222222222222222"
        val MAKER = Address.parse("0x09eD1F966745Be18C711C346242c0974DAd7c3e5")
        val RELAYER = Address.parse("0x507d1d152025e9f6da7bc03b358acc247f07b4eb")
        val AUTH = Address.parse("0x4444444444444444444444444444444444444444")
        val CONTRACT = Address.parse("0x32CE55D00E6184c385E44e6b20b76d3a8407E809")
        val TOKEN = Address.parse("0x5764D0044bef5AA839E0dDafE2073421101B9Ed8")
        val AMOUNT = Usdc6.ofMicros(1_000_000)
        val USER_SHARE = SwapShare.of(filled(64, 0x0c))
        val MAKER_SHARE = SwapShare.of(filled(64, 0x0a))
        val NOTE_COMMITMENT = NoteCommitment.of(filled(32, 0x05))
        val notes =
            mapOf(
                RailgunKeySource.BIP85 to note(npk = 1, commitment = NOTE_COMMITMENT),
                RailgunKeySource.ZCASH_SEED to note(npk = 0x11, commitment = NoteCommitment.of(filled(32, 0x15))),
            )
        val ONE_UNIT = Usdc6.ofMicros(1)
        val SWAP_ID = SwapId.of(MAKER, USER_SHARE)
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
                maxRelayerFee = Usdc6.ofMicros(100_000),
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
