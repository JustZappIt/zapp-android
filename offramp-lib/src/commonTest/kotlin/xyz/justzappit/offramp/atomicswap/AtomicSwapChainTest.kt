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
import xyz.justzappit.evm.abi.AbiArray
import xyz.justzappit.evm.abi.AbiBool
import xyz.justzappit.evm.abi.AbiBytes
import xyz.justzappit.evm.abi.AbiDecoder
import xyz.justzappit.evm.abi.AbiEncoder
import xyz.justzappit.evm.abi.AbiTuple
import xyz.justzappit.evm.abi.Selector4
import xyz.justzappit.evm.abi.keccak256
import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.math.bigIntegerValueOf
import xyz.justzappit.evm.multicall.Multicall3
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
    fun aWindowsContractsSwapIsReadFromGetSwapsFourWordsAsTheTermsItsHashShows() {
        val swap = AtomicSwapChain.decodeSwap(swapWords(stage = 2), TERMS, WINDOWS)!!
        assertEquals(TERMS, swap.terms)
        assertEquals(SwapStage.READY, swap.stage)
        assertTrue(swap.paidOut)
        assertContentEquals(filled(0x0e), swap.secret)
        assertNull(AtomicSwapChain.decodeSwap(ByteArray(4 * 32), TERMS, WINDOWS))
    }

    @Test
    fun aRetiredLockContractsSwapIsReadFromItsSixWords() {
        val swap = AtomicSwapChain.decodeSwap(lockWords(stage = 4), TERMS, windows = null)!!
        assertEquals(SwapStage.REFUNDED, swap.stage)
        assertContentEquals(filled(0x0e), swap.secret, "the share follows its two locks")
        assertNull(AtomicSwapChain.decodeSwap(ByteArray(6 * 32), TERMS, windows = null))
    }

    @Test
    fun theDeploymentDecidesHowARecordReadsNeverTheAnswersLength() {
        // The lock contract's six words would read as the windows contract's four, its locks taken for the share.
        val asWindows =
            assertFailsWith<AtomicSwapBlockedException> { AtomicSwapChain.decodeState(lockWords(2), WINDOWS) }
        assertEquals(AtomicSwapBlock.WRONG_DEPLOYMENT, asWindows.reason)
        val asLocks = assertFailsWith<AtomicSwapBlockedException> { AtomicSwapChain.decodeState(swapWords(2), null) }
        assertEquals(AtomicSwapBlock.CHAIN_UNREADABLE, asLocks.reason)
    }

    @Test
    fun otherTermsAnotherAbiOrAnUnreadableAnswerAreRefused() {
        val refused =
            listOf(
                AtomicSwapBlock.MISMATCH to { AtomicSwapChain.decodeSwap(swapWords(2), OTHER_TERMS, WINDOWS) },
                AtomicSwapBlock.WRONG_DEPLOYMENT to { AtomicSwapChain.decodeSwap(ByteArray(16 * 32), TERMS, WINDOWS) },
                AtomicSwapBlock.CHAIN_UNREADABLE to { AtomicSwapChain.decodeSwap(ByteArray(3 * 32), TERMS, WINDOWS) },
                AtomicSwapBlock.CHAIN_UNREADABLE to { AtomicSwapChain.decodeSwap(swapWords(9), TERMS, WINDOWS) },
            )
        for ((reason, read) in refused) {
            assertEquals(reason, assertFailsWith<AtomicSwapBlockedException> { read() }.reason)
        }
    }

    @Test
    fun noSwapIsReadBeforeTheContractShowsItIsThePinnedWindowsContract() =
        runTest {
            val wrong =
                listOf(
                    "the lock contract" to Contract(windows = null, lockDuration = 600),
                    "another minimum window" to Contract(windows = WINDOWS.copy(min = WINDOWS.min + 1)),
                    "another late window" to Contract(windows = WINDOWS.copy(late = 86_400)),
                    "windows and locks" to Contract(lockDuration = 600),
                )
            for ((case, contract) in wrong) {
                val node = Node(contract = contract)
                val id = SwapId.of(filled(0x5c))
                val reads =
                    listOf<suspend () -> Unit>(
                        { node.chain.swap(id) },
                        { node.chain.confirmedSwap(id, TERMS) },
                        { node.chain.swapNow(id, TERMS) },
                        { node.chain.swapAt(id, TERMS, LATEST - 2) },
                        { node.chain.read(id, TERMS) },
                    )
                for (read in reads) {
                    assertEquals(
                        AtomicSwapBlock.WRONG_DEPLOYMENT,
                        assertFailsWith<AtomicSwapBlockedException> { read() }.reason,
                        case,
                    )
                }
                assertTrue(node.callTags(GET_SWAP).isEmpty(), case)
                assertTrue(node.aggregated.none { calls -> calls.any { it.second.startsWith(GET_SWAP) } }, case)
                node.close()
            }
        }

    @Test
    fun aRetiredDeploymentReadsOnlyTheLockContractItPinsAndNeverJudgesAReveal() =
        runTest {
            val id = SwapId.of(filled(0x5c))
            val windows = Node(retired = true)
            assertEquals(
                AtomicSwapBlock.WRONG_DEPLOYMENT,
                assertFailsWith<AtomicSwapBlockedException> { windows.chain.swap(id) }.reason,
            )
            windows.close()

            val locks = Node(retired = true, contract = Contract(windows = null, lockDuration = 600))
            assertEquals(SwapStage.OPEN, locks.chain.swap(id)?.stage)
            val judged = assertFailsWith<AtomicSwapBlockedException> { locks.chain.swapNow(id, TERMS) }
            assertEquals(AtomicSwapBlock.RETIRED_CONTRACT, judged.reason)
            locks.close()
        }

    @Test
    fun theContractsIdentityIsReadOnceInOneMulticallEachAllowedToRevert() =
        runTest {
            val node = Node()
            val id = SwapId.of(filled(0x5c))

            node.chain.read(id, TERMS)
            node.chain.confirmedSwap(id, TERMS)
            node.chain.swap(id)

            val identity = node.aggregated.filter { calls -> calls.any { it.second == "MIN_WINDOW()" } }
            assertEquals(
                listOf(listOf("MIN_WINDOW()", "LATE_WINDOW()", "LOCK_DURATION()").map { true to it }),
                identity.map { calls -> calls.map { it.first to it.second } },
            )
            node.close()
        }

    @Test
    fun theConsistentReadIsOneEthCallAtTheNewestBlockForTheSwapAndThatBlocksTimeAndNumber() =
        runTest {
            val node = Node()
            val id = SwapId.of(filled(0x5c))

            val read = node.chain.swapNow(id, TERMS)

            assertEquals(ChainHead(LATEST, NOW), read.head)
            assertEquals(SwapStage.OPEN, read.swap?.stage)
            val consistent = node.aggregated.last()
            assertEquals(
                listOf(
                    false to "$GET_SWAP:${id.hex}",
                    false to "getCurrentBlockTimestamp()",
                    false to "getBlockNumber()",
                ),
                consistent.map { it.first to it.second },
            )
            assertEquals(listOf("latest"), node.aggregateTags.distinct())
            assertTrue(node.callTags(GET_SWAP).isEmpty(), "never a second read of its own")
            node.close()
        }

    @Test
    fun aSwapIsReadAsOfABlockBelowTheNewestByItsNumber() =
        runTest {
            val node = Node()

            node.chain.swapAt(SwapId.of(filled(0x5c)), TERMS, LATEST - 2)

            assertEquals(listOf("0x" + (LATEST - 2).toString(16)), node.callTags(GET_SWAP))
            node.close()
        }

    @Test
    fun anOversizedBlockTimeOrNumberInTheConsistentReadIsRefused() =
        runTest {
            for (oversized in listOf("time", "number")) {
                val node = Node()
                if (oversized == "time") node.blockTime = HUGE else node.blockNumber = HUGE
                assertFailsWith<IllegalArgumentException> { node.chain.swapNow(SwapId.of(filled(0x5c)), TERMS) }
                node.close()
            }
        }

    @Test
    fun aRefundsTimeIsItsConfirmedEventsBlocks() =
        runTest {
            val node = Node()
            val id = SwapId.of(filled(0x5c))
            node.logs = "[${eventLog(REFUNDED_TOPIC, block = LATEST - 40)}]"

            assertEquals(NOW - 40 * 12, node.chain.refundedAt(id, NOW - 3_600))
            assertEquals(listOf("0x" + (LATEST - 40).toString(16)), node.blocksAsked)
            val topics = node.logFilters.single().getValue("topics")
            assertEquals(REFUNDED_TOPIC, topics.jsonArray[0].jsonPrimitive.content)
            node.logs = "[${eventLog(PAID_OUT_TOPIC, block = LATEST - 40)}]"
            assertNull(node.chain.refundedAt(id, NOW - 3_600), "a payout is no refund")
            node.close()
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
            val judged = assertFailsWith<AtomicSwapBlockedException> { node.chain.swapNow(id, TERMS) }

            assertEquals(AtomicSwapBlock.WRONG_DEPLOYMENT, swap.reason)
            assertEquals(AtomicSwapBlock.WRONG_DEPLOYMENT, payout.reason)
            assertEquals(AtomicSwapBlock.WRONG_DEPLOYMENT, judged.reason)
            assertTrue(node.callTags(GET_SWAP).isEmpty())
            assertTrue(node.aggregated.isEmpty())
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
    fun oversizedRpcHeadAndFundingBlockAreRejected() =
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
            node.fundingWords = filled(0x06) + ByteArray(23) + byteArrayOf(1) + ByteArray(8)
            assertFailsWith<IllegalArgumentException> { node.chain.read(SwapId.of(filled(0x5c)), TERMS) }
            node.close()
        }

    /** What the contract at the deployment's address answers of its identity: its windows, and its lock duration. */
    private data class Contract(
        val windows: SwapWindows? = WINDOWS,
        val lockDuration: Long? = null,
    )

    /** A JSON-RPC node that answers what the reader asks, Multicall3 included, and remembers how it was asked. */
    private class Node(
        chainId: Long = 11_155_111,
        confirmations: Long = 3,
        private val contract: Contract = Contract(),
        retired: Boolean = false,
    ) {
        val logFilters = mutableListOf<JsonObject>()
        var logs = "[${payoutLog()}]"
        private val calls = mutableListOf<Pair<String, String>>()

        /** Each Multicall3 read: whether each call could fail, and what it asked, by its function. */
        val aggregated = mutableListOf<List<Pair<Boolean, String>>>()
        val aggregateTags = mutableListOf<String>()

        /** The blocks asked for by number. */
        val blocksAsked = mutableListOf<String>()
        var head = HEAD_BLOCK
        var blockTime: BigInteger = bigIntegerValueOf(NOW)
        var blockNumber: BigInteger = bigIntegerValueOf(LATEST)
        var fundingWords: ByteArray = filled(0x06) + uint(LATEST - 5)
        var known = false
        var receipt: Pair<Long, String>? = null
        private val deployment =
            DEPLOYMENT.copy(escrowConfirmations = confirmations, windows = if (retired) null else WINDOWS)
        private val engine =
            MockEngine { request ->
                val body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                val payload = Json.parseToJsonElement(body).jsonObject
                val params = payload["params"]!!.jsonArray
                val result =
                    when (payload["method"]!!.jsonPrimitive.content) {
                        "eth_chainId" -> "\"0x${chainId.toString(16)}\""
                        "eth_getBlockByNumber" -> block(params[0].jsonPrimitive.content)
                        "eth_call" -> call(params)
                        "eth_getLogs" -> logs.also { logFilters += params[0].jsonObject }
                        "eth_getTransactionReceipt" -> receiptJson()
                        "eth_getTransactionByHash" -> if (known) """{"hash":"$FUNDING_TX"}""" else "null"
                        else -> error("unexpected ${payload["method"]}")
                    }
                respond(
                    result?.let { """{"jsonrpc":"2.0","id":1,"result":$it}""" }
                        ?: """{"jsonrpc":"2.0","id":1,"error":{"code":3,"message":"execution reverted"}}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        private val http = HttpClient(engine) { install(ContentNegotiation) { json() } }
        val chain = AtomicSwapChain(BaseRpcClient(http, "http://mock/rpc"), deployment)

        fun callTags(signature: String) = calls.filter { it.first == selector(signature) }.map { it.second }

        fun close() = http.close()

        private fun block(tag: String): String {
            if (tag != "latest") {
                blocksAsked += tag
                val number = tag.removePrefix("0x").toLong(16)
                return """{"number":"$tag","timestamp":"0x${(NOW - (LATEST - number) * 12).toString(16)}"}"""
            }
            return head
        }

        // Null where the call reverts.
        private fun call(params: JsonArray): String? {
            val request = params[0].jsonObject
            val to = Address.parse(request.getValue("to").jsonPrimitive.content)
            val data = request.getValue("data").jsonPrimitive.content
            val bytes = data.hexToBytes()
            val tag = params[1].jsonPrimitive.content
            val answered = if (to == Multicall3.ADDRESS) aggregate(bytes, tag) else direct(to, bytes, tag)
            return answered?.let { "\"0x${it.toHex()}\"" }
        }

        private fun direct(
            to: Address,
            data: ByteArray,
            tag: String,
        ): ByteArray? {
            calls += ("0x" + data.copyOf(4).toHex()) to tag
            return answer(to, data)
        }

        // As Multicall3 answers `aggregate3`: reverted, where a call that may not fail does.
        private fun aggregate(
            data: ByteArray,
            tag: String,
        ): ByteArray? {
            aggregateTags += tag
            val asked = aggregated(data)
            aggregated += asked.map { (_, allowFailure, call) -> allowFailure to named(call) }
            val results = asked.map { (target, allowFailure, call) -> allowFailure to answer(target, call) }
            val reverts = results.any { (allowFailure, answer) -> !allowFailure && answer == null }
            val tuples =
                results.map { (_, answer) ->
                    AbiTuple(listOf(AbiBool(answer != null), AbiBytes(answer ?: ByteArray(0))))
                }
            return if (reverts) null else AbiEncoder.encode(listOf(AbiArray(tuples)))
        }

        // Multicall3's own reads: the block's time and number.
        private fun clock(selector: String): ByteArray =
            when (selector) {
                selector("getCurrentBlockTimestamp()") -> word(blockTime)
                selector("getBlockNumber()") -> word(blockNumber)
                else -> error("unexpected Multicall3 call $selector")
            }

        private fun answer(
            to: Address,
            data: ByteArray,
        ): ByteArray? {
            val selector = "0x" + data.copyOf(4).toHex()
            return when {
                to == Multicall3.ADDRESS -> clock(selector)
                selector == selector("MIN_WINDOW()") -> contract.windows?.let { uint(it.min) }
                selector == selector("LATE_WINDOW()") -> contract.windows?.let { uint(it.late) }
                selector == selector("LOCK_DURATION()") -> contract.lockDuration?.let { uint(it) }
                selector == selector(GET_SWAP) -> if (contract.windows == null) lockWords(1) else swapWords(1)
                selector == selector(REVERSE_FUNDING) -> fundingWords
                selector == selector(VAULT_OF) -> address("7777777777777777777777777777777777777777")
                selector == selector(BALANCE_OF) -> uint(900_000)
                else -> error("unexpected call $selector")
            }
        }

        private fun named(call: ByteArray): String {
            val selector = "0x" + call.copyOf(4).toHex()
            val name = NAMES.firstOrNull { selector(it) == selector } ?: selector
            return if (name == GET_SWAP) "$name:0x${call.copyOfRange(4, 36).toHex()}" else name
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
        const val VAULT_OF = "vaultOf(bytes32)"
        const val BALANCE_OF = "balanceOf(address)"
        val NAMES =
            listOf(
                GET_SWAP,
                "MIN_WINDOW()",
                "LATE_WINDOW()",
                "LOCK_DURATION()",
                "getCurrentBlockTimestamp()",
                "getBlockNumber()",
            )
        val WINDOWS = SwapWindows(min = 1_800, late = 3_600)
        val HUGE = bigIntegerValueOf(1).shiftLeft(64)
        val PAYOUT_TX = "0x" + "a1".repeat(32)
        val FUNDING_TX = "0x" + "b2".repeat(32)
        val PAID_OUT_TOPIC = "0x" + keccak256("PaidOut(bytes32,address,uint256)".encodeToByteArray()).toHex()
        val REFUNDED_TOPIC = "0x" + keccak256("Refunded(bytes32,uint256)".encodeToByteArray()).toHex()
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
            )

        fun selector(signature: String) = Selector4.fromCanonicalSignature(signature).hex

        fun payoutLog(
            block: Long = LATEST - 2,
            removed: Boolean = false,
            contract: String = DEPLOYMENT.contract.lowercaseHex,
            topic: String = PAID_OUT_TOPIC,
            id: String = SwapId.of(filled(0x5c)).hex,
            data: String = "0x" + (address(DEPLOYMENT.relayer.lowercaseHex) + uint(20_000)).toHex(),
        ) = """{"address":"$contract","topics":["$topic","$id"],"data":"$data","removed":$removed,""" +
            """"blockNumber":"0x${block.toString(16)}","transactionHash":"$PAYOUT_TX","logIndex":"0x0"}"""

        fun eventLog(
            topic: String,
            block: Long,
        ) = payoutLog(block = block, topic = topic, data = "0x" + filled(0x0e).toHex())

        val TERMS = ZecSwapVectors.PAYS_RAILGUN
        val OTHER_TERMS = TERMS.copy(t1 = TERMS.t1 + 1)

        fun swapWords(stage: Int) =
            listOf(TERMS.hash(), uint(stage.toLong()), uint(1), filled(0x0e)).reduce(ByteArray::plus)

        fun lockWords(stage: Int) =
            listOf(TERMS.hash(), uint(stage.toLong()), uint(1), uint(1_789_999_000), uint(1_790_000_100), filled(0x0e))
                .reduce(ByteArray::plus)

        fun uint(value: Long) = word(bigIntegerValueOf(value))

        fun word(value: BigInteger) = value.toByteArray().let { ByteArray(32 - it.size) + it }

        fun address(hex: String) = ByteArray(12) + hex.hexToBytes()

        fun filled(byte: Int) = ByteArray(32) { byte.toByte() }

        /** The calls of an `aggregate3` calldata: each target, whether it may fail, and its calldata. */
        fun aggregated(data: ByteArray): List<Triple<Address, Boolean, ByteArray>> {
            val body = data.copyOfRange(4, data.size)
            val words = AbiDecoder(body)
            val array = words.uint(0).toInt() / 32
            val count = words.uint(array).toInt()
            val elements = array + 1
            return List(count) { index ->
                val tuple = elements + words.uint(elements + index).toInt() / 32
                val bytes = tuple + words.uint(tuple + 2).toInt() / 32
                val length = words.uint(bytes).toInt()
                val start = (bytes + 1) * 32
                Triple(words.address(tuple), words.uint8(tuple + 1) == 1, body.copyOfRange(start, start + length))
            }
        }
    }
}
