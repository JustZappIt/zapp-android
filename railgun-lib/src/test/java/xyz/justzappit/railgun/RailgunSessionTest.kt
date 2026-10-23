// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.railgun

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.evm.util.toHex
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Instant

class RailgunSessionTest {
    private val page = FakePage()
    private val session =
        RailgunSession(page, RailgunNetwork.SEPOLIA, ZERO_K_ADDRESS, RailgunFees(25))

    @Test
    fun balancesKeepKnownBucketsOnly() =
        runTest {
            page.reply =
                """{"balances":{"Spendable":[{"token":"$TOKEN","amount":"123456789012345678901234567890"}],""" +
                """"ShieldPending":[],"SomeNewBucket":[{"token":"$TOKEN","amount":"1"}]},"received":[]}"""

            assertEquals(
                RailgunBalances(
                    mapOf(
                        RailgunBalanceBucket.SPENDABLE to
                            listOf(RailgunTokenAmount(TOKEN_ADDRESS, BigInteger("123456789012345678901234567890"))),
                        RailgunBalanceBucket.SHIELD_PENDING to emptyList(),
                    )
                ),
                session.refresh().balances,
            )
            assertEquals(RailgunMethod.REFRESH, page.method)
        }

    @Test
    fun theSendsReceivedComeWithTheBalancesAndOneItCannotReadIsLeftOut() =
        runTest {
            page.reply =
                """{"balances":{},"received":[""" +
                """{"txid":"$TXID","timestamp":1790000000,"token":"$TOKEN","amount":"5000000",""" +
                """"bucket":"Spendable","sender":"$ZERO_K"},""" +
                """{"txid":"$TXID","timestamp":null,"token":"$TOKEN","amount":"1","bucket":"SomeNewBucket",""" +
                """"sender":"0zk1notanaddress"},""" +
                """{"txid":"$TXID","timestamp":1790000000,"token":"$TOKEN","amount":"1.5","bucket":"Spendable"},""" +
                """{"txid":"0x1234","token":"$TOKEN","amount":"1"}]}"""

            assertEquals(
                listOf(
                    RailgunReceivedSend(
                        txid = TxHash.fromHex(TXID),
                        token = TOKEN_ADDRESS,
                        amount = BigInteger.valueOf(5_000_000),
                        bucket = RailgunBalanceBucket.SPENDABLE,
                        blockTime = Instant.fromEpochSeconds(1_790_000_000),
                        sender = ZERO_K_ADDRESS,
                    ),
                    RailgunReceivedSend(TxHash.fromHex(TXID), TOKEN_ADDRESS, ONE, null, null, null),
                ),
                session.refresh().received,
            )
        }

    @Test
    fun aHistoryThePageCouldNotReadIsNullAndTheBalancesStand() =
        runTest {
            for (received in listOf(""","received":null""", "", ""","received":{"txid":"$TXID"}""")) {
                page.reply = """{"balances":{"Spendable":[{"token":"$TOKEN","amount":"1"}]}$received}"""

                val refreshed = session.refresh()

                assertNull(refreshed.received, received)
                assertEquals(1, refreshed.balances.byBucket.size)
            }
        }

    @Test
    fun aSendReceivedIsSettledOnceSpendableOrSpent() {
        val send = RailgunReceivedSend(TxHash.fromHex(TXID), TOKEN_ADDRESS, ONE, null, null, null)

        assertEquals(
            listOf(true, true, false, false, false),
            listOf(
                RailgunBalanceBucket.SPENDABLE,
                RailgunBalanceBucket.SPENT,
                RailgunBalanceBucket.MISSING_EXTERNAL_POI,
                RailgunBalanceBucket.SHIELD_PENDING,
                null,
            ).map { send.copy(bucket = it).isSettled },
        )
    }

    @Test
    fun aDepositNoteIsItsFiveWordsInTheContractsOrderForThisWallet() =
        runTest {
            page.reply = depositNote()

            val note = session.depositNote()

            assertEquals(RailgunMethod.DEPOSIT_NOTE, page.method)
            assertEquals(ZERO_K_ADDRESS, note.receiver)
            assertEquals(WORDS[0], note.npk.word())
            assertEquals(WORDS.subList(1, 4), note.encryptedBundle.map { it.word() })
            assertEquals(WORDS[4], note.shieldKey.word())
            assertEquals(note, session.depositNote())
            assertEquals("RailgunDepositNote", note.toString())
        }

    @Test
    fun aDepositNoteGivesOutCopiesOfItsWords() =
        runTest {
            page.reply = depositNote()
            val note = session.depositNote()

            note.npk.fill(0)
            note.encryptedBundle.forEach { it.fill(0) }
            note.shieldKey.fill(0)

            val words = listOf(note.npk) + note.encryptedBundle + listOf(note.shieldKey)
            assertEquals(WORDS, words.map { it.word() })
        }

    @Test
    fun aDepositNoteThatIsNotFiveWordsIsAProtocolFailure() =
        runTest {
            val notes =
                listOf(
                    depositNote(bundle = WORDS.subList(1, 3)),
                    depositNote(bundle = WORDS.subList(1, 5)),
                    depositNote(npk = "0x" + "11".repeat(31)),
                    depositNote(shieldKey = "0x" + "zz".repeat(32)),
                    """{"npk":"${WORDS[0]}","encryptedBundle":["${WORDS[1]}","${WORDS[2]}","${WORDS[3]}"]}""",
                )
            for (reply in notes) {
                page.reply = reply
                assertFailsWith<RailgunException.Protocol>(reply) { session.depositNote() }
            }
        }

    @Test
    fun theDestinationPicksTheCallAndTheBroadcasterGetsItsFee() =
        runTest {
            page.reply = relayed()

            val private = session.prove(transfer(RailgunDestination.Private(ZERO_K_ADDRESS)), BROADCASTER, FEE)
            assertEquals(RailgunMethod.TRANSFER, page.method)
            assertEquals(ZERO_K, page.params("to"))
            assertEquals("3598677", page.params("fee"))
            assertEquals(ZERO_K, page.broadcaster("railgunAddress"))
            session.prove(transfer(RailgunDestination.Public(TOKEN_ADDRESS)), BROADCASTER, FEE)
            assertEquals(RailgunMethod.UNSHIELD, page.method)
            assertEquals(TOKEN_ADDRESS.checksumHex, page.params("to"))

            assertEquals(RailgunRelayRequest(CHAIN_ID, PROXY_ADDRESS, DATA, BigInteger.ZERO), private.request)
            assertEquals(listOf(RailgunNullifiers(0, listOf(NULLIFIER))), private.spends)
        }

    @Test
    fun aTransactionThatIsNotATransactCallForTheBroadcasterIsRefused() =
        runTest {
            val replies =
                listOf(relayed(to = TOKEN), relayed(chainId = 1), relayed(value = "1"), relayed(spends = "[]"))
            for (reply in replies) {
                page.reply = reply
                assertFailsWith<RailgunException.Protocol> {
                    session.prove(transfer(RailgunDestination.Private(ZERO_K_ADDRESS)), BROADCASTER, FEE)
                }
            }
        }

    @Test
    fun aResultItCannotReadIsAProtocolFailure() =
        runTest {
            page.reply = """{"chainId":$CHAIN_ID,"to":"$PROXY"}"""

            assertFailsWith<RailgunException.Protocol> {
                session.prove(transfer(RailgunDestination.Private(ZERO_K_ADDRESS)), BROADCASTER, FEE)
            }
        }

    @Test
    fun aDestinationIsWrittenAsTheAddressItGoesTo() {
        val private = RailgunDestination.Private(ZERO_K_ADDRESS)
        val public = RailgunDestination.Public(TOKEN_ADDRESS)

        assertEquals("\"$ZERO_K\"", Json.encodeToString(RailgunDestination.Serializer, private))
        assertEquals("\"${TOKEN_ADDRESS.checksumHex}\"", Json.encodeToString(RailgunDestination.Serializer, public))
        assertEquals(private, Json.decodeFromString(RailgunDestination.Serializer, "\"$ZERO_K\""))
        assertEquals(public, Json.decodeFromString(RailgunDestination.Serializer, "\"$TOKEN\""))
    }

    @Test
    fun theUnshieldFeeRoundsDown() {
        assertEquals(BigInteger.valueOf(2_499), RailgunFees(25).unshieldFee(BigInteger.valueOf(999_999)))
    }

    private fun transfer(to: RailgunDestination) = RailgunTransfer(to, TOKEN_ADDRESS, ONE)

    private fun relayed(
        to: String = PROXY,
        chainId: Long = CHAIN_ID,
        value: String = "0",
        spends: String = """[{"tree":0,"nullifiers":["$NULLIFIER"]}]""",
    ) = """{"chainId":$chainId,"to":"$to","data":"$DATA","value":"$value","spends":$spends,"proofMs":1200}"""

    private fun depositNote(
        npk: String = WORDS[0],
        bundle: List<String> = WORDS.subList(1, 4),
        shieldKey: String = WORDS[4],
    ) = """{"npk":"$npk","encryptedBundle":[${bundle.joinToString(",") { "\"$it\"" }}],"shieldKey":"$shieldKey"}"""

    private fun ByteArray.word() = "0x" + toHex()

    private class FakePage : RailgunPage {
        var reply = "null"
        var method: RailgunMethod? = null
        private var sent: JsonElement? = null

        override val isOpen = true

        override suspend fun call(
            method: RailgunMethod,
            params: JsonElement
        ): JsonElement {
            this.method = method
            sent = params
            return Json.parseToJsonElement(reply)
        }

        override suspend fun close() = Unit

        fun params(name: String): String? = (sent as? JsonObject)?.get(name)?.jsonPrimitive?.content

        fun broadcaster(name: String): String? =
            (sent as? JsonObject)
                ?.get("broadcaster")
                ?.jsonObject
                ?.get(name)
                ?.jsonPrimitive
                ?.content
    }

    private companion object {
        const val ZERO_K =
            "0zk1qyrs4qyrd08p6uep0fc2y8njktgcpezts3rpaq6q0ln948ecjkw8prv7j6f" +
                "e3z53llz8ursderja0juwv5pgnv8x5klmmwkv8q38h9n704h4d4qjyw7n5qk68nx"
        val ZERO_K_ADDRESS = RailgunAddress(ZERO_K)
        const val TOKEN = "0x5764d0044bef5aa839e0ddafe2073421101b9ed8"
        val TOKEN_ADDRESS = Address.parse(TOKEN)
        val ONE: BigInteger = BigInteger.ONE
        const val CHAIN_ID = 11_155_111L
        const val PROXY = "0xeCFCf3b4eC647c4Ca6D49108b311b7a7C9543fea"
        val PROXY_ADDRESS = Address.parse(PROXY)
        const val DATA = "0xd8ae136a00"
        val NULLIFIER = "0x" + "ab".repeat(32)
        val TXID = "0x" + "cd".repeat(32)

        // npk, the three encryptedBundle words and shieldKey, as zecSwap's known answers write a note: 0x22… to 0x66….
        val WORDS = listOf("22", "33", "44", "55", "66").map { "0x" + it.repeat(32) }
        val BROADCASTER =
            RailgunBroadcaster(
                chainId = CHAIN_ID,
                railgunProxy = PROXY_ADDRESS,
                railgunAddress = ZERO_K_ADDRESS,
                feeToken = TOKEN_ADDRESS,
                minFee = BigInteger.valueOf(250_000),
                maxGasPrice = BigInteger.valueOf(20_000_000_000),
            )
        val FEE: BigInteger = BigInteger.valueOf(3_598_677)
    }
}
