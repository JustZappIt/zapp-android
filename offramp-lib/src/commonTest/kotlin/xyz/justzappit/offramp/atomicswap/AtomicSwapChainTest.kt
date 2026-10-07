// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.atomicswap

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.justzappit.evm.abi.Selector4
import xyz.justzappit.evm.abi.keccak256
import xyz.justzappit.evm.math.bigIntegerValueOf
import xyz.justzappit.evm.rpc.BaseRpcClient
import xyz.justzappit.evm.rpc.TransactionStatus
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.types.ChainId
import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.evm.util.hexToBytes
import xyz.justzappit.evm.util.toHex
import xyz.justzappit.offramp.p2p.Usdc6
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AtomicSwapChainTest {
    @Test
    fun idsAndTermsHashMatchZecSwapsVectors() {
        assertEquals(ZecSwapVectors.SWAP_ID, SwapId.of(ZecSwapVectors.MAKER, ZecSwapVectors.USER_SHARE).hex)
        val reverse = ReverseSwapId.of(ZecSwapVectors.AUTH, ZecSwapVectors.MAKER_SHARE)
        assertEquals(ZecSwapVectors.REVERSE_SWAP_ID, reverse.hex)
        assertNotEquals(SwapId.of(ZecSwapVectors.AUTH, ZecSwapVectors.MAKER_SHARE), reverse)
        assertEquals(ZecSwapVectors.PAYS_ACCOUNT_HASH, ZecSwapVectors.PAYS_ACCOUNT.hash().hex())
        assertEquals(ZecSwapVectors.PAYS_RAILGUN_HASH, ZecSwapVectors.PAYS_RAILGUN.hash().hex())
        assertEquals(ZecSwapVectors.REVERSE_HASH, ZecSwapVectors.REVERSE.hash().hex())
    }

    @Test
    fun aSwapIsReadFromGetSwapsSixWordsAsTheTermsItsHashShows() {
        val swap = AtomicSwapChain.decodeSwap(swapWords(stage = 2), TERMS)!!
        assertEquals(TERMS, swap.terms)
        assertEquals(SwapStage.READY, swap.stage)
        assertTrue(swap.paidOut)
        assertEquals(1_789_999_000, swap.claimLockUntil)
        assertEquals(1_790_000_100, swap.refundLockUntil)
        assertContentEquals(filled(0x0e), swap.secret)
        assertNull(AtomicSwapChain.decodeSwap(ByteArray(6 * 32), TERMS))
    }

    @Test
    fun otherTermsAnotherAbiOrAnUnreadableAnswerAreRefused() {
        val refused =
            listOf(
                AtomicSwapBlock.MISMATCH to { AtomicSwapChain.decodeSwap(swapWords(2), TERMS.copy(t1 = TERMS.t1 + 1)) },
                AtomicSwapBlock.WRONG_DEPLOYMENT to { AtomicSwapChain.decodeSwap(ByteArray(16 * 32), TERMS) },
                AtomicSwapBlock.CHAIN_UNREADABLE to { AtomicSwapChain.decodeSwap(ByteArray(5 * 32), TERMS) },
                AtomicSwapBlock.CHAIN_UNREADABLE to { AtomicSwapChain.decodeSwap(swapWords(stage = 9), TERMS) },
            )
        for ((reason, read) in refused) {
            assertEquals(reason, assertFailsWith<AtomicSwapBlockedException> { read() }.reason)
        }
    }

    @Test
    fun aPayoutRequiresAConfirmedEventFromThisContractAndSwap() =
        runTest {
            val node = Node()
            val id = SwapId.of(filled(0x5c))

            val tx = node.chain.payoutTx(id, near = NOW - 3_600)

            assertEquals(TxHash.fromHex(PAYOUT_TX), tx)
            val filter = node.logFilters.single()
            assertEquals("0x" + (LATEST - 2 - 5_000 + 1).toString(16), filter["fromBlock"]!!.jsonPrimitive.content)
            assertEquals("0x" + (LATEST - 2).toString(16), filter["toBlock"]!!.jsonPrimitive.content)
            assertEquals(id.hex, filter["topics"]!!.jsonArray[1].jsonPrimitive.content)
            val payout = node.chain.confirmedPayout(id, NOW)!!
            assertEquals(DEPLOYMENT.relayer, payout.relayer)
            assertEquals(Usdc6.ofMicros(20_000), payout.fee)
            node.close()
        }

    @Test
    fun aRemovedUnconfirmedOrUnrelatedLogCannotConfirmAPayout() =
        runTest {
            val node = Node()
            val id = SwapId.of(filled(0x5c))
            val unrelated =
                listOf(
                    payoutLog(removed = true),
                    payoutLog(block = LATEST - 1),
                    payoutLog(contract = DEPLOYMENT.maker.lowercaseHex),
                    payoutLog(topic = "0x" + filled(0x01).toHex()),
                    payoutLog(id = SwapId.of(filled(0x01)).hex),
                )
            unrelated.forEach { log ->
                node.logs = "[$log]"
                assertNull(node.chain.confirmedPayout(id, NOW))
            }
            node.logs = "[${payoutLog(data = "0x")}]"
            val malformed = assertFailsWith<AtomicSwapBlockedException> { node.chain.confirmedPayout(id, NOW) }
            assertEquals(AtomicSwapBlock.CHAIN_UNREADABLE, malformed.reason)
            node.close()
        }

    @Test
    fun aDelayedPayoutCanBeFoundAcrossBoundedPages() =
        runTest {
            val node = Node()
            node.logs = "[${payoutLog(block = LATEST - 10_000)}]"

            val payout = node.chain.confirmedPayout(SwapId.of(filled(0x5c)), NOW - 12 * 15_000)

            assertEquals(TxHash.fromHex(PAYOUT_TX), payout?.transaction)
            assertEquals(2, node.logFilters.size)
            var previousFrom = LATEST - 1
            node.logFilters.forEach { filter ->
                val from =
                    filter["fromBlock"]!!
                        .jsonPrimitive.content
                        .removePrefix("0x")
                        .toLong(16)
                val to =
                    filter["toBlock"]!!
                        .jsonPrimitive.content
                        .removePrefix("0x")
                        .toLong(16)
                assertEquals(previousFrom - 1, to)
                assertTrue(to - from + 1 in 1..5_000)
                previousFrom = from
            }
            node.close()
        }

    @Test
    fun forwardAuthorizationUsesTheConfiguredDepthWhileKeyReuseChecksUseTheTip() =
        runTest {
            val node = Node(confirmations = 5)
            val id = SwapId.of(filled(0x5c))

            node.chain.confirmedSwap(id, TERMS)
            node.chain.swap(id)

            assertEquals(listOf("0x" + (LATEST - 4).toString(16), "latest"), node.callTags(GET_SWAP))
            node.close()
        }

    @Test
    fun aNodeOnAnotherChainCannotAuthorizeAForwardSwapOrConfirmAPayout() =
        runTest {
            val node = Node(chainId = 1)
            val id = SwapId.of(filled(0x5c))

            val swap = assertFailsWith<AtomicSwapBlockedException> { node.chain.confirmedSwap(id, TERMS) }
            val payout = assertFailsWith<AtomicSwapBlockedException> { node.chain.confirmedPayout(id, NOW) }

            assertEquals(AtomicSwapBlock.WRONG_DEPLOYMENT, swap.reason)
            assertEquals(AtomicSwapBlock.WRONG_DEPLOYMENT, payout.reason)
            assertTrue(node.callTags(GET_SWAP).isEmpty())
            assertTrue(node.logFilters.isEmpty())
            node.close()
        }

    @Test
    fun aReverseSwapIsReadWhereItsEscrowHasItsConfirmations() =
        runTest {
            val node = Node()

            val state = node.chain.read(SwapId.of(filled(0x5c)), TERMS)

            val confirmed = "0x" + (LATEST - 3 + 1).toString(16)
            assertEquals(listOf(confirmed, confirmed), node.callTags(GET_SWAP) + node.callTags(REVERSE_FUNDING))
            assertEquals(SwapStage.OPEN, state.swap?.stage)
            assertEquals(NoteCommitment.of(filled(0x06)), state.refundNote)
            assertEquals(LATEST - 5, state.fundingBlock)
            assertEquals(LATEST, state.block)
            assertEquals(NOW, state.now)
            assertEquals(600, state.lockDuration)
            node.close()
        }

    @Test
    fun theLockDurationIsReadOnceForBothDirections() =
        runTest {
            val node = Node()

            node.chain.read(SwapId.of(filled(0x5c)), TERMS)
            node.chain.read(SwapId.of(filled(0x5c)), TERMS)

            assertEquals(600, node.chain.lockDuration())
            assertEquals(1, node.callTags(LOCK_DURATION).size)
            node.close()
        }

    @Test
    fun aNodeOnAnotherChainIsNeverReadForAReverseSwap() =
        runTest {
            val node = Node(chainId = 1)

            val refused =
                assertFailsWith<AtomicSwapBlockedException> { node.chain.read(SwapId.of(filled(0x5c)), TERMS) }
            assertEquals(AtomicSwapBlock.WRONG_DEPLOYMENT, refused.reason)
            assertTrue(node.callTags(GET_SWAP).isEmpty())
            node.close()
        }

    @Test
    fun aFundingCountsOnlyOnceItHasItsConfirmations() =
        runTest {
            val node = Node()
            val funding = TxHash.fromHex(FUNDING_TX)

            assertEquals(TransactionStatus.UNKNOWN, node.chain.fundingStatus(funding))
            node.known = true
            assertEquals(TransactionStatus.PENDING, node.chain.fundingStatus(funding))
            node.receipt = LATEST - 1 to "0x1"
            assertEquals(TransactionStatus.PENDING, node.chain.fundingStatus(funding))
            node.receipt = LATEST - 2 to "0x1"
            assertEquals(TransactionStatus.CONFIRMED, node.chain.fundingStatus(funding))
            node.receipt = LATEST - 2 to "0x0"
            assertEquals(TransactionStatus.REVERTED, node.chain.fundingStatus(funding))
            node.close()
        }

    @Test
    fun aVaultHoldsWhatTheTokenSaysItDoes() =
        runTest {
            val node = Node()

            assertEquals(Usdc6.ofMicros(900_000), node.chain.vaultBalance(SwapId.of(filled(0x5c))))
            node.close()
        }

    @Test
    fun oversizedSwapTimestampsNeverWrapIntoValidLocks() {
        val oversized = byteArrayOf(1) + ByteArray(8)
        for (word in listOf(3, 4)) {
            val data = swapWords(stage = 1)
            oversized.copyInto(data, word * 32 + 32 - oversized.size)
            assertFailsWith<IllegalArgumentException> { AtomicSwapChain.decodeSwap(data, TERMS) }
        }
    }

    @Test
    fun oversizedRpcHeadDurationAndFundingBlockAreRejected() =
        runTest {
            val node = Node()
            for (field in listOf("number", "timestamp")) {
                node.head =
                    if (field == "number") {
                        """{"number":"0x10000000000000001","timestamp":"0x1"}"""
                    } else {
                        """{"number":"0x1","timestamp":"0x10000000000000001"}"""
                    }
                assertFailsWith<IllegalArgumentException> { node.chain.now() }
            }
            node.head = HEAD_BLOCK
            node.overrideWords = ByteArray(23) + byteArrayOf(1) + ByteArray(8)
            assertFailsWith<IllegalArgumentException> { node.chain.lockDuration() }
            node.overrideCall = REVERSE_FUNDING
            node.overrideWords = filled(0x06) + checkNotNull(node.overrideWords)
            assertFailsWith<IllegalArgumentException> { node.chain.read(SwapId.of(filled(0x5c)), TERMS) }
            node.close()
        }

    /** A JSON-RPC node that answers what the reader asks, and remembers how it was asked. */
    private class Node(
        chainId: Long = 11_155_111,
        confirmations: Long = 3,
    ) {
        val logFilters = mutableListOf<JsonObject>()
        var logs = "[${payoutLog()}]"
        private val calls = mutableListOf<Pair<String, String>>()
        var head = HEAD_BLOCK
        var overrideWords: ByteArray? = null
        var overrideCall = LOCK_DURATION
        var known = false
        var receipt: Pair<Long, String>? = null
        private val engine =
            MockEngine { request ->
                val body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                val payload = Json.parseToJsonElement(body).jsonObject
                val params = payload["params"]!!.jsonArray
                val result =
                    when (payload["method"]!!.jsonPrimitive.content) {
                        "eth_chainId" -> "\"0x${chainId.toString(16)}\""
                        "eth_getBlockByNumber" -> head
                        "eth_call" -> call(params)
                        "eth_getLogs" -> logs.also { logFilters += params[0].jsonObject }
                        "eth_getTransactionReceipt" -> receiptJson()
                        "eth_getTransactionByHash" -> if (known) """{"hash":"$FUNDING_TX"}""" else "null"
                        else -> error("unexpected ${payload["method"]}")
                    }
                respond(
                    """{"jsonrpc":"2.0","id":1,"result":$result}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        private val http = HttpClient(engine) { install(ContentNegotiation) { json() } }
        val chain =
            AtomicSwapChain(
                BaseRpcClient(http, "http://mock/rpc"),
                DEPLOYMENT.copy(escrowConfirmations = confirmations),
            )

        fun callTags(signature: String) = calls.filter { it.first == selector(signature) }.map { it.second }

        fun close() = http.close()

        private fun call(params: JsonArray): String {
            val data = params[0].jsonObject["data"]!!.jsonPrimitive.content
            val selector = data.take(10)
            calls += selector to params[1].jsonPrimitive.content
            val words =
                when (selector) {
                    selector(GET_SWAP) -> swapWords(stage = 1)
                    selector(REVERSE_FUNDING) -> filled(0x06) + uint(LATEST - 5)
                    selector(LOCK_DURATION) -> uint(600)
                    selector(VAULT_OF) -> address("7777777777777777777777777777777777777777")
                    selector(BALANCE_OF) -> uint(900_000)
                    else -> error("unexpected call $selector")
                }
            return "\"0x${(overrideWords?.takeIf { selector == selector(overrideCall) } ?: words).toHex()}\""
        }

        private fun receiptJson(): String =
            receipt?.let { (block, status) ->
                """{"transactionHash":"$FUNDING_TX","blockNumber":"0x${block.toString(16)}","status":"$status",""" +
                    """"gasUsed":"0x1"}"""
            } ?: "null"
    }

    private companion object {
        const val LATEST = 11_790_175L
        const val NOW = 1_790_000_000L
        const val GET_SWAP = "getSwap(bytes32)"
        const val REVERSE_FUNDING = "reverseFunding(bytes32)"
        const val LOCK_DURATION = "LOCK_DURATION()"
        const val VAULT_OF = "vaultOf(bytes32)"
        const val BALANCE_OF = "balanceOf(address)"
        val PAYOUT_TX = "0x" + "a1".repeat(32)
        val FUNDING_TX = "0x" + "b2".repeat(32)
        val HEAD_BLOCK = """{"number":"0x${LATEST.toString(16)}","timestamp":"0x${NOW.toString(16)}"}"""
        val DEPLOYMENT =
            SwapDeployment(
                makerUrl = Url("http://maker"),
                relayerUrl = Url("http://relayer"),
                rpcUrl = Url("http://mock/rpc"),
                chainId = ChainId(11_155_111),
                contract = Address.parse("0x32CE55D00E6184c385E44e6b20b76d3a8407E809"),
                token = Address.parse("0x5764D0044bef5AA839E0dDafE2073421101B9Ed8"),
                railgunProxy = Address.parse("0xeCFCf3b4eC647c4Ca6D49108b311b7a7C9543fea"),
                maker = Address.parse("0x09eD1F966745Be18C711C346242c0974DAd7c3e5"),
                relayer = Address.parse("0x507d1d152025e9F6DA7Bc03B358acc247f07b4eB"),
                maxRelayerFee = Usdc6.ofMicros(100_000),
            )

        fun selector(signature: String) = Selector4.fromCanonicalSignature(signature).hex

        fun payoutLog(
            block: Long = LATEST - 2,
            removed: Boolean = false,
            contract: String = DEPLOYMENT.contract.lowercaseHex,
            topic: String = "0x" + keccak256("PaidOut(bytes32,address,uint256)".encodeToByteArray()).toHex(),
            id: String = SwapId.of(filled(0x5c)).hex,
            data: String = "0x" + (address(DEPLOYMENT.relayer.lowercaseHex) + uint(20_000)).toHex(),
        ) =
            """{"address":"$contract","topics":["$topic","$id"],"data":"$data","removed":$removed,""" +
                """"blockNumber":"0x${block.toString(16)}","transactionHash":"$PAYOUT_TX","logIndex":"0x0"}"""

        val TERMS = ZecSwapVectors.PAYS_RAILGUN

        fun swapWords(stage: Int) =
            listOf(TERMS.hash(), uint(stage.toLong()), uint(1), uint(1_789_999_000), uint(1_790_000_100), filled(0x0e))
                .reduce(ByteArray::plus)

        fun uint(value: Long) = bigIntegerValueOf(value).toByteArray().let { ByteArray(32 - it.size) + it }

        fun address(hex: String) = ByteArray(12) + hex.hexToBytes()

        fun filled(byte: Int) = ByteArray(32) { byte.toByte() }
    }
}
