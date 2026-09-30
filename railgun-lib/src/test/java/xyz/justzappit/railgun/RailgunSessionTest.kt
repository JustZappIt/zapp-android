// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.railgun

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.justzappit.evm.abi.keccak256
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.evm.util.toHex
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds

class RailgunSessionTest {
    private val page = FakePage()
    private val session =
        RailgunSession(page, RailgunNetwork.SEPOLIA, ZERO_K_ADDRESS, RailgunFees(25, 25), gasAccountAddress = null)

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
    fun theDestinationPicksTheCall() =
        runTest {
            page.reply = signed(RAW)

            val private = session.sign(RailgunTransfer(RailgunDestination.Private(ZERO_K_ADDRESS), TOKEN_ADDRESS, ONE))
            assertEquals(RailgunMethod.TRANSFER, page.method)
            assertEquals(ZERO_K, page.params("to"))
            session.sign(RailgunTransfer(RailgunDestination.Public(TOKEN_ADDRESS), TOKEN_ADDRESS, ONE))
            assertEquals(RailgunMethod.UNSHIELD, page.method)
            assertEquals(TOKEN_ADDRESS.checksumHex, page.params("to"))

            assertEquals(TxHash(keccak256(RAW)), private.txHash)
            assertEquals(GAS_ACCOUNT, private.from)
            assertEquals(7, private.nonce)
            assertEquals(1_200.milliseconds, private.proofDuration)
        }

    @Test
    fun aHashThatIsNotTheTransactionsIsRefused() =
        runTest {
            page.reply =
                """{"raw":"0x${RAW.toHex()}","txHash":"0x${"11".repeat(32)}","from":"$GAS","nonce":7,"proofMs":1200}"""

            assertFailsWith<RailgunException.Protocol> { session.signShield(ONE) }
        }

    @Test
    fun aResultItCannotReadIsAProtocolFailure() =
        runTest {
            page.reply = """{"raw":"0x${RAW.toHex()}","txHash":"0x${keccak256(RAW).toHex()}"}"""

            assertFailsWith<RailgunException.Protocol> {
                session.sign(RailgunTransfer(RailgunDestination.Private(ZERO_K_ADDRESS), TOKEN_ADDRESS, ONE))
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
        assertEquals(BigInteger.valueOf(2_499), RailgunFees(25, 25).unshieldFee(BigInteger.valueOf(999_999)))
    }

    private fun signed(raw: ByteArray) =
        """{"raw":"0x${raw.toHex()}","txHash":"0x${keccak256(raw).toHex()}","from":"$GAS","nonce":7,"proofMs":1200}"""

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
    }

    private companion object {
        const val ZERO_K =
            "0zk1qyrs4qyrd08p6uep0fc2y8njktgcpezts3rpaq6q0ln948ecjkw8prv7j6f" +
                "e3z53llz8ursderja0juwv5pgnv8x5klmmwkv8q38h9n704h4d4qjyw7n5qk68nx"
        val ZERO_K_ADDRESS = RailgunAddress(ZERO_K)
        const val TOKEN = "0x5764d0044bef5aa839e0ddafe2073421101b9ed8"
        val TOKEN_ADDRESS = Address.parse(TOKEN)
        val ONE: BigInteger = BigInteger.ONE
        val RAW = byteArrayOf(2, 1, 3)
        const val GAS = "0x90c670d5752546412b56b5372d2b720b6bbefae6"
        val GAS_ACCOUNT = Address.parse(GAS)
    }
}
