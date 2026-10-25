// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.deposits

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.justzappit.evm.abi.AbiAddress
import xyz.justzappit.evm.abi.AbiArray
import xyz.justzappit.evm.abi.AbiBool
import xyz.justzappit.evm.abi.AbiBytes
import xyz.justzappit.evm.abi.AbiBytes32
import xyz.justzappit.evm.abi.AbiDecoder
import xyz.justzappit.evm.abi.AbiEncoder
import xyz.justzappit.evm.abi.AbiString
import xyz.justzappit.evm.abi.AbiTuple
import xyz.justzappit.evm.abi.AbiUint
import xyz.justzappit.evm.abi.Selector4
import xyz.justzappit.evm.deposits.DepositNote
import xyz.justzappit.evm.deposits.DepositsDomain
import xyz.justzappit.evm.deposits.TokenDomain
import xyz.justzappit.evm.multicall.Multicall3
import xyz.justzappit.evm.rpc.BaseRpcClient
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.util.hexToBytes
import xyz.justzappit.evm.util.toHex
import xyz.justzappit.offramp.atomicswap.NoteCommitment
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DepositsChainTest {
    @Test
    fun `a window of addresses reads their balances and deposits in one read, any revert failing it`() =
        runTest {
            val node = Node()
            val owners = List(GAP) { owner(it) }
            node.balances[owner(3)] = 5_000_000
            node.deposited += owner(7)

            val uses = node.chain.uses(owners)

            assertEquals(GAP, uses.size)
            assertEquals(DepositAddressUse(units(5_000_000), false), uses[3])
            assertEquals(DepositAddressUse(units(0), true), uses[7])
            assertEquals(listOf(3, 7), uses.withIndex().filter { it.value.isUsed }.map { it.index })
            val read = node.aggregates.single()
            assertEquals(2 * GAP, read.size)
            assertTrue(read.none { it.allowFailure })
            val asked = listOf(SEPOLIA_PIN.token to "balanceOf(address)", SEPOLIA_PIN.contract to "deposited(address)")
            assertEquals(List(GAP) { asked }.flatten(), read.map { it.target to it.function })
            node.close()
        }

    @Test
    fun `the live test dollar's domain is its own name and version's, and the Deposits contract takes it`() =
        runTest {
            // tUSD's name(), version() and DOMAIN_SEPARATOR(), and the Deposits contract's TOKEN(), on Sepolia on
            // 2026-10-23.
            val node = Node()

            val domain = node.chain.tokenDomain()

            assertEquals(TokenDomain("ZecSwap Test USD", "2", SEPOLIA_PIN.chainId, SEPOLIA_PIN.token), domain)
            assertContentEquals(LIVE_SEPARATOR, domain.separator())
            node.close()
        }

    @Test
    fun `a token whose separator isn't its domain's, or that the contract doesn't take, is refused`() =
        runTest {
            val node = Node()
            node.separator = ByteArray(32) { 1 }
            assertEquals(
                DepositProblem.WRONG_DEPLOYMENT,
                assertFailsWith<DepositsException> { node.chain.tokenDomain() }.problem,
            )
            node.separator = LIVE_SEPARATOR
            node.contractToken = Address.parse("0x5764D0044bef5AA839E0dDafE2073421101B9Ed8")
            assertEquals(
                DepositProblem.WRONG_DEPLOYMENT,
                assertFailsWith<DepositsException> { node.chain.tokenDomain() }.problem,
            )
            node.close()
        }

    @Test
    fun `a node on another chain is refused`() =
        runTest {
            val node = Node(chainId = 1)
            val refusal = assertFailsWith<DepositsException> { node.chain.requireChain() }
            assertEquals(DepositProblem.WRONG_DEPLOYMENT, refusal.problem)
            node.close()
        }

    @Test
    fun `vaults, rescue nonces and used authorizations read from the contract and the token`() =
        runTest {
            val node = Node()
            node.vaultBalances[vault(owner(2))] = 700_000

            assertEquals(listOf(units(0), units(700_000)), node.chain.vaultBalances(listOf(owner(1), owner(2))))
            assertEquals(4, node.chain.rescueNonce(owner(2)))
            assertTrue(node.chain.authorizationUsed(owner(2), USED_NONCE))
            assertFalse(node.chain.authorizationUsed(owner(2), ByteArray(32)))
            node.close()
        }

    @Test
    fun `only the pinned contract's deposit events, naming whose and which note, are read from a receipt`() =
        runTest {
            val node = Node()
            val note = NoteCommitment.of(ByteArray(32) { 0x4d })
            node.logs =
                listOf(
                    log(DEPOSIT_SHIELDED_TOPIC, owner(2), note),
                    log(RESCUED_TOPIC, owner(3), note),
                    // Another contract's, a removed one, and one of another shape.
                    log(DEPOSIT_SHIELDED_TOPIC, owner(2), note, contract = "0x" + "99".repeat(20)),
                    log(DEPOSIT_SHIELDED_TOPIC, owner(2), note, removed = true),
                    log(DEPOSIT_SHIELDED_TOPIC, owner(2), note, words = 3),
                    log("0x" + "12".repeat(32), owner(2), note),
                )

            val events = node.chain.events(tx(0x31))

            fun event(
                kind: DepositKind,
                from: Address,
            ) = DepositEvent(kind, from, units(4_990_000), units(110_000), SEPOLIA_PIN.relayer, note)
            assertEquals(listOf(event(DepositKind.SHIELD, owner(2)), event(DepositKind.RESCUE, owner(3))), events)
            node.logs = null
            assertEquals(emptyList(), node.chain.events(tx(0x31)))
            node.close()
        }

    @Test
    fun `the event topics are the contract's`() {
        assertEquals("0x61717b10e9648a499f8cab024e59e1045e7fa1a0c85ef39219860684ecf52691", DEPOSIT_SHIELDED_TOPIC)
    }

    @Test
    fun `the Kotlin nonce and commitment are the ones the live Sepolia Deposits contract answers`() {
        // `cast call` of the pinned Sepolia Deposits contract's depositNonce and noteCommitment at block 11,980,858
        // (2026-10-23), for npk 0x22…, the bundle 0x33…, 0x44…, 0x55…,
        // shieldKey 0x66…, a fee of 150000 and the relayer's deposits account: the contract hashes the chain id and its
        // own address in, as the Kotlin does, and the bundle's three words inline.
        val note =
            DepositNote(
                ByteArray(32) { 0x22 },
                listOf(ByteArray(32) { 0x33 }, ByteArray(32) { 0x44 }, ByteArray(32) { 0x55 }),
                ByteArray(32) { 0x66 },
            )
        val deposits = DepositsDomain(SEPOLIA_PIN.chainId, SEPOLIA_PIN.contract)
        assertEquals(LIVE_NONCE, "0x" + deposits.depositNonce(note, units(150_000), SEPOLIA_PIN.relayer).toHex())
        assertEquals(LIVE_COMMITMENT, "0x" + note.commitment.toHex())
    }

    /** A JSON-RPC node holding [SEPOLIA_PIN]'s contract and token, Multicall3 included, as the live ones answer. */
    private class Node(
        chainId: Long = 11_155_111,
    ) {
        val balances = mutableMapOf<Address, Long>()
        val deposited = mutableSetOf<Address>()
        val vaultBalances = mutableMapOf<Address, Long>()
        var separator = LIVE_SEPARATOR
        var contractToken = SEPOLIA_PIN.token
        var logs: List<String>? = emptyList()
        val aggregates = mutableListOf<List<Asked>>()
        private val engine =
            MockEngine { request ->
                val body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                val payload = Json.parseToJsonElement(body).jsonObject
                val params = payload.getValue("params").jsonArray
                val result =
                    when (payload.getValue("method").jsonPrimitive.content) {
                        "eth_chainId" -> "\"0x${chainId.toString(16)}\""
                        "eth_call" -> call(params)
                        "eth_getTransactionReceipt" -> receipt()
                        else -> error("unexpected ${payload["method"]}")
                    }
                respond(
                    """{"jsonrpc":"2.0","id":1,"result":$result}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        private val http = HttpClient(engine) { install(ContentNegotiation) { json() } }
        val chain = DepositsChain(BaseRpcClient(http, "http://mock/rpc"), SEPOLIA_PIN)

        fun close() = http.close()

        private fun call(params: JsonArray): String {
            val request = params[0].jsonObject
            val to = Address.parse(request.getValue("to").jsonPrimitive.content)
            val data =
                request
                    .getValue("data")
                    .jsonPrimitive.content
                    .hexToBytes()
            val answer =
                if (to == Multicall3.ADDRESS) {
                    val asked = aggregated(data).also { aggregates += it }
                    val tuples = asked.map { AbiTuple(listOf(AbiBool(true), AbiBytes(answer(it.target, it.data)))) }
                    AbiEncoder.encode(listOf(AbiArray(tuples)))
                } else {
                    answer(to, data)
                }
            return "\"0x${answer.toHex()}\""
        }

        // What the pinned token and contract answer, by who's asked what: owner is the call's first argument.
        private val answers: Map<Pair<Address, String>, (Address?, ByteArray) -> ByteArray> =
            mapOf(
                (SEPOLIA_PIN.token to "balanceOf(address)") to { owner, _ ->
                    uint(balances[owner] ?: vaultBalances[owner] ?: 0)
                },
                (SEPOLIA_PIN.contract to "deposited(address)") to { owner, _ ->
                    uint(if (owner in deposited) 1 else 0)
                },
                (SEPOLIA_PIN.contract to "vaultOf(address)") to { owner, _ ->
                    AbiEncoder.encode(listOf(AbiAddress(vault(checkNotNull(owner)))))
                },
                (SEPOLIA_PIN.contract to "rescueNonces(address)") to { _, _ -> uint(4) },
                (SEPOLIA_PIN.token to "authorizationState(address,bytes32)") to { _, data ->
                    uint(if (data.copyOfRange(36, 68).contentEquals(USED_NONCE)) 1 else 0)
                },
                (SEPOLIA_PIN.contract to "TOKEN()") to { _, _ -> AbiEncoder.encode(listOf(AbiAddress(contractToken))) },
                (SEPOLIA_PIN.token to "name()") to { _, _ -> AbiEncoder.encode(listOf(AbiString("ZecSwap Test USD"))) },
                (SEPOLIA_PIN.token to "version()") to { _, _ -> AbiEncoder.encode(listOf(AbiString("2"))) },
                (SEPOLIA_PIN.token to "DOMAIN_SEPARATOR()") to { _, _ ->
                    AbiEncoder.encode(listOf(AbiBytes32(separator)))
                },
            )

        private fun answer(
            to: Address,
            data: ByteArray,
        ): ByteArray {
            val selector = "0x" + data.copyOf(4).toHex()
            val owner = data.takeIf { it.size >= 36 }?.let { Address.fromBytes(it.copyOfRange(16, 36)) }
            val answer =
                answers.entries.firstOrNull { (asked, _) -> asked.first == to && selector(asked.second) == selector }
            return checkNotNull(answer) { "unexpected call $selector to $to" }.value(owner, data)
        }

        private fun receipt(): String =
            logs?.let {
                """{"transactionHash":"${tx(0x31).hex}","blockNumber":"0x10","status":"0x1","gasUsed":"0x1",""" +
                    """"logs":[${it.joinToString(",")}]}"""
            } ?: "null"
    }

    private class Asked(
        val target: Address,
        val allowFailure: Boolean,
        val data: ByteArray,
    ) {
        val function: String get() = FUNCTIONS.first { selector(it) == "0x" + data.copyOf(4).toHex() }
    }

    private companion object {
        val LIVE_SEPARATOR = "0x213081332edff57e3793dd54b7845a3c6ca4fcab305d4bef25eaa7bd476b286b".hexToBytes()
        const val LIVE_NONCE = "0xbe2970b10d01883236e5590c158e6a38e96e52fca56d6460261a1f61db0d4747"
        const val LIVE_COMMITMENT = "0x4df47be579bd966f5104be761d3cc5704c776403467845b092d22901e91d24b0"
        val USED_NONCE = ByteArray(32) { 0x0d }
        val FUNCTIONS = listOf("balanceOf(address)", "deposited(address)")

        fun selector(signature: String) = Selector4.fromCanonicalSignature(signature).hex

        fun owner(index: Int) = Address.fromBytes(ByteArray(20) { (index + 1).toByte() })

        // A vault the contract derives for [owner]: here, its bytes reversed.
        fun vault(owner: Address) = Address.fromBytes(owner.bytes.reversedArray().also { it[0] = 0x0a })

        fun uint(value: Long) = AbiEncoder.encode(listOf(AbiUint(units(value))))

        fun log(
            topic: String,
            from: Address,
            note: NoteCommitment,
            contract: String = SEPOLIA_PIN.contract.lowercaseHex,
            removed: Boolean = false,
            words: Int = 4,
        ): String {
            val data =
                AbiEncoder
                    .encode(
                        listOf(
                            AbiUint(units(4_990_000)),
                            AbiUint(units(110_000)),
                            AbiAddress(SEPOLIA_PIN.relayer),
                            AbiBytes32(note.bytes),
                        ),
                    ).copyOf(words * AbiDecoder.WORD)
            val indexed = "0x" + (ByteArray(12) + from.bytes).toHex()
            return """{"address":"$contract","topics":["$topic","$indexed"],"data":"0x${data.toHex()}",""" +
                """"removed":$removed,"blockNumber":"0x10","transactionHash":"${tx(0x31).hex}","logIndex":"0x0"}"""
        }

        /** The calls of an `aggregate3` calldata: each target, whether it may fail, and its calldata. */
        fun aggregated(data: ByteArray): List<Asked> {
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
                Asked(words.address(tuple), words.uint8(tuple + 1) == 1, body.copyOfRange(start, start + length))
            }
        }
    }
}
