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
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RailgunSessionTest {
    private val page = FakePage()
    private val session =
        RailgunSession(page, RailgunNetwork.SEPOLIA, ZERO_K_ADDRESS, RailgunFees(25))

    @Test
    fun balancesKeepKnownBucketsOnly() =
        runTest {
            page.reply =
                """{"Spendable":[{"token":"$TOKEN","amount":"123456789012345678901234567890"}],""" +
                """"ShieldPending":[],"SomeNewBucket":[{"token":"$TOKEN","amount":"1"}]}"""

            assertEquals(
                RailgunBalances(
                    mapOf(
                        RailgunBalanceBucket.SPENDABLE to
                            listOf(RailgunTokenAmount(TOKEN_ADDRESS, BigInteger("123456789012345678901234567890"))),
                        RailgunBalanceBucket.SHIELD_PENDING to emptyList(),
                    )
                ),
                session.refresh(),
            )
            assertEquals(RailgunMethod.REFRESH, page.method)
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
