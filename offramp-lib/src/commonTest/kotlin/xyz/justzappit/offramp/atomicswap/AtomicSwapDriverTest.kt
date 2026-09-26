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
import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.math.bigIntegerValueOf
import xyz.justzappit.evm.types.Address
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AtomicSwapDriverTest {
    @Test
    fun happyPathClaimsIntoRailgun() =
        runTest {
            val h = Harness()
            val record = h.driver.deposit(h.driver.open(units = 1))
            assertEquals(listOf("utest1deposit" to DEPOSIT_ZAT), h.zcash.payments)
            assertEquals("0xdeposit", record.depositTxId)

            h.chain.stage = SwapStage.READY
            val step = h.driver.advance(record)

            assertEquals(AtomicSwapStep.Finished("paid into Railgun"), step)
            assertEquals(
                listOf("/v1/quote", "/v1/quote/$QUOTE_ID/accept", "/v1/terms", "/v1/lock-claim", "/v1/claim"),
                h.paths,
            )
            assertTrue(h.bodies.getValue("/v1/claim").contains("0x" + "09".repeat(32)), "the claim reveals z")
            assertEquals("paid into Railgun", h.store.record?.outcome)
        }

    @Test
    fun anInterruptedDepositIsNeverPaidAgain() =
        runTest {
            val h = Harness()
            val record = h.driver.open(units = 1).copy(depositAttempted = true)
            assertFailsWith<IllegalStateException> { h.driver.deposit(record) }
            assertTrue(h.zcash.payments.isEmpty())
        }

    @Test
    fun nothingIsDepositedForAPayoutToSomeoneElse() =
        runTest {
            val h = Harness()
            val record = h.driver.open(units = 1)
            h.chain.payoutNote = ByteArray(32) { 6 }
            val refused = assertFailsWith<IllegalStateException> { h.driver.deposit(record) }
            assertEquals("the payout goes to someone else", refused.message)
            assertTrue(h.zcash.payments.isEmpty())
        }

    @Test
    fun nothingIsDepositedWhenT0IsTooSoon() =
        runTest {
            val h = Harness()
            val record = h.driver.open(units = 1)
            h.chain.t0 = NOW + 60
            assertFailsWith<IllegalStateException> { h.driver.deposit(record) }
            assertTrue(h.zcash.payments.isEmpty())
        }

    @Test
    fun aRefundSweepsTheDepositHome() =
        runTest {
            val h = Harness()
            val record = h.driver.deposit(h.driver.open(units = 1))
            h.chain.stage = SwapStage.REFUNDED
            h.chain.secret = ByteArray(32) { 0x0e }

            val step = h.driver.advance(record)

            assertEquals(AtomicSwapStep.Finished("refunded: the deposit came home in 0xsweep"), step)
            assertEquals(listOf(Triple(record.index, MAKER_SHARE.toList(), record.zcashHeight)), h.zcash.sweeps)
        }

    @Test
    fun theShareStaysSecretWhenRailgunIsNotTakingPayouts() =
        runTest {
            val h = Harness()
            val record = h.driver.deposit(h.driver.open(units = 1))
            h.chain.stage = SwapStage.READY
            h.chain.railgunAccepts = false

            assertFailsWith<IllegalStateException> { h.driver.advance(record) }
            assertTrue("/v1/claim" !in h.paths)
        }

    @Test
    fun aClaimRevealedBeforeAnInterruptionOnlyNeedsItsPayout() =
        runTest {
            val h = Harness()
            val record = h.driver.deposit(h.driver.open(units = 1))
            h.chain.stage = SwapStage.CLAIMED

            assertEquals(AtomicSwapStep.Finished("paid into Railgun"), h.driver.advance(record))
            assertEquals("/v1/payout", h.paths.last())
            assertTrue("/v1/claim" !in h.paths)
        }

    private class Harness {
        val chain = FakeChain()
        val zcash = FakeZcash()
        val store = FakeStore()
        val paths = mutableListOf<String>()
        val bodies = mutableMapOf<String, String>()
        private val engine =
            MockEngine { request ->
                val path = request.url.encodedPath
                paths += path
                bodies[path] = (request.body as? TextContent)?.text.orEmpty()
                val body =
                    when {
                        path == "/v1/quote" -> {
                            QUOTE_JSON
                        }

                        path.endsWith("/accept") -> {
                            """{"swapId":"${SWAP_ID.hex()}"}"""
                        }

                        path == "/v1/terms" -> {
                            TERMS_JSON
                        }

                        path == "/v1/lock-claim" -> {
                            sent(1).also { chain.claimLockUntil = NOW + LOCK_SECONDS }
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
                respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
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
            )

        private fun sent(transactions: Int): String {
            val hashes = List(transactions) { "\"0x$it\"" }.joinToString()
            return """{"transactions":[$hashes]}"""
        }
    }

    private class FakeChain : AtomicSwapChainReader {
        var stage = SwapStage.OPEN
        var t0 = NOW + 35 * 60
        var claimLockUntil = 0L
        var paidOut = false
        var payoutNote = NOTE_COMMITMENT
        var secret = ByteArray(32)
        var railgunAccepts = true

        override suspend fun swap(id: ByteArray): OnChainSwap =
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
                refundLockUntil = 0,
                makerShare = MAKER_SHARE,
                userShare = USER_SHARE,
                secret = secret,
                payoutNote = payoutNote,
            )

        override suspend fun now() = NOW

        override suspend fun railgunAccepts(token: Address) = railgunAccepts
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

        override suspend fun chainHeight() = 4_200_000L

        override suspend fun pay(
            address: String,
            zatoshi: Long
        ): String {
            payments += address to zatoshi
            return "0xdeposit"
        }

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
