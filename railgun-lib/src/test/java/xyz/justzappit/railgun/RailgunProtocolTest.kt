// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.railgun

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import xyz.justzappit.railgun.RailgunProtocol.Message
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class RailgunProtocolTest {
    @Test
    fun requestCarriesIdMethodAndParams() {
        val params = buildJsonObject { put("network", "sepolia") }
        val request = Json.parseToJsonElement(RailgunProtocol.request(7, RailgunMethod.OPEN_WALLET, params)).jsonObject

        assertEquals(JsonPrimitive(7), request["id"])
        assertEquals(JsonPrimitive("openWallet"), request["method"])
        assertEquals(params, request["params"])
    }

    @Test
    fun decodesReplies() {
        assertEquals(
            Message.Reply(3, buildJsonObject { put("address", "0zk1") }),
            RailgunProtocol.decode("""{"id":3,"result":{"address":"0zk1"}}"""),
        )
        assertEquals(Message.Reply(4, JsonNull), RailgunProtocol.decode("""{"id":4,"result":null}"""))
    }

    @Test
    fun failuresKeepTheirKind() {
        assertIs<RailgunException.Protocol>(failure("""{"code":"BAD_REQUEST","message":"no wallet is open"}"""))
        assertIs<RailgunException.Failed>(failure("""{"code":"FAILED","message":"the RPC timed out"}"""))
        assertEquals("the RPC timed out", failure("""{"code":"FAILED","message":"the RPC timed out"}""").message)
    }

    @Test
    fun anUnknownCodeIsAFailure() {
        assertIs<RailgunException.Failed>(failure("""{"code":"SOMETHING_NEW","message":"?"}"""))
    }

    @Test
    fun aReplyItCannotReadStillFailsItsCall() {
        val message = assertIs<Message.Failure>(RailgunProtocol.decode("""{"id":5,"error":"a bare string"}"""))

        assertEquals(5, message.id)
        assertIs<RailgunException.Protocol>(message.error)
    }

    @Test
    fun decodesEvents() {
        assertEquals(Message.Ready, RailgunProtocol.decode("""{"event":"ready","data":null}"""))
        assertEquals(
            Message.Event(RailgunEvent.Scan(RailgunMerkletree.TXID, RailgunScanStatus.UPDATED, 0.4f)),
            RailgunProtocol.decode("""{"event":"scan","data":{"tree":"txid","status":"Updated","progress":0.4}}"""),
        )
        assertEquals(
            Message.Event(RailgunEvent.Proof(0.25f, "proving")),
            RailgunProtocol.decode("""{"event":"proof","data":{"progress":0.25,"status":"proving"}}"""),
        )
        assertEquals(
            Message.Event(RailgunEvent.Log("scanning")),
            RailgunProtocol.decode("""{"event":"log","data":"scanning"}"""),
        )
    }

    @Test
    fun dropsWhatItDoesNotUnderstand() {
        assertNull(RailgunProtocol.decode(null))
        assertNull(RailgunProtocol.decode("not json"))
        assertNull(RailgunProtocol.decode("[1]"))
        assertNull(RailgunProtocol.decode("""{"event":"unknown","data":1}"""))
        assertNull(RailgunProtocol.decode("""{"event":"scan","data":{"tree":"other","status":"Updated"}}"""))
        assertNull(RailgunProtocol.decode("""{"result":1}"""))
    }

    @Test
    fun thePageAnswersEveryMethod() {
        val page = File("web/src/index.js").readText()

        assertEquals(RailgunMethod.entries.map(::wireName).sorted(), handlers(page, "handlers").sorted())
    }

    private fun wireName(method: RailgunMethod) =
        RailgunProtocol.json
            .encodeToJsonElement(RailgunMethod.serializer(), method)
            .jsonPrimitive.content

    private fun handlers(
        page: String,
        table: String
    ) = Regex("""const $table = \{([^}]*)}""")
        .find(page)
        .let { checkNotNull(it).groupValues[1] }
        .lines()
        .filterNot { it.trim().startsWith("//") }
        .joinToString("")
        .split(',')
        .map { it.substringBefore(':').trim() }
        .filter { it.isNotEmpty() }

    @Test
    fun railgunAddressesAreChecked() {
        assertEquals(ZERO_K, RailgunAddress.parseOrNull(ZERO_K)?.value)
        assertEquals(ZERO_K, RailgunAddress.parseOrNull(ZERO_K.uppercase())?.value)
        assertNull(RailgunAddress.parseOrNull("0zk1abc"))
        assertNull(RailgunAddress.parseOrNull("0x09ed1f966745be18c711c346242c0974dad7c3e5"))
        assertNull(RailgunAddress.parseOrNull(" $ZERO_K"))
    }

    @Test
    fun aMistypedCharacterFailsTheChecksum() {
        ZERO_K.indices.drop(PREFIX.length).forEach { index ->
            val typo = ZERO_K.replaceRange(index, index + 1, if (ZERO_K[index] == 'q') "p" else "q")
            assertNull(RailgunAddress.parseOrNull(typo), "accepted a typo at $index")
        }
        assertNull(RailgunAddress.parseOrNull(ZERO_K.dropLast(1)))
        assertNull(RailgunAddress.parseOrNull(ZERO_K.replaceRange(0, PREFIX.length, "0zl1")))
        assertFailsWith<IllegalArgumentException> { RailgunAddress(ZERO_K.dropLast(1) + "q") }
    }

    private fun failure(error: String): RailgunException =
        assertIs<Message.Failure>(RailgunProtocol.decode("""{"id":1,"error":$error}""")).error

    private companion object {
        const val PREFIX = "0zk1"

        // Railgun wallet 0 of a seed of 64 bytes of 0x08, as zecSwap's vectors derive it.
        const val ZERO_K =
            "0zk1qyrs4qyrd08p6uep0fc2y8njktgcpezts3rpaq6q0ln948ecjkw8prv7j6f" +
                "e3z53llz8ursderja0juwv5pgnv8x5klmmwkv8q38h9n704h4d4qjyw7n5qk68nx"
    }
}
