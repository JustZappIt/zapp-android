// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.railgun

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RailgunProtocolTest {
    @Test
    fun requestCarriesIdMethodAndParams() {
        val params = buildJsonObject { put("network", "sepolia") }
        val request = Json.parseToJsonElement(RailgunProtocol.request(7, "start", params))
        assertEquals(JsonPrimitive(7), request.jsonObject["id"])
        assertEquals(JsonPrimitive("start"), request.jsonObject["method"])
        assertEquals(JsonPrimitive("sepolia"), request.jsonObject.getValue("params").jsonObject["network"])
    }

    @Test
    fun decodesResultsAndFailures() {
        assertEquals(
            RailgunProtocol.Message.Result(3, buildJsonObject { put("address", "0zk1") }),
            RailgunProtocol.decode("""{"id":3,"result":{"address":"0zk1"}}"""),
        )
        assertEquals(
            RailgunProtocol.Message.Failure(4, "no wallet is open"),
            RailgunProtocol.decode("""{"id":4,"error":"no wallet is open"}"""),
        )
    }

    @Test
    fun decodesEvents() {
        assertEquals(RailgunProtocol.Message.Ready, RailgunProtocol.decode("""{"event":"ready","data":null}"""))
        assertEquals(
            RailgunProtocol.Message.Event(RailgunEvent.Scan(RailgunMerkletree.TXID, RailgunScanStatus.UPDATED, 0.4f)),
            RailgunProtocol.decode("""{"event":"scan","data":{"tree":"txid","status":"Updated","progress":0.4}}"""),
        )
        assertEquals(
            RailgunProtocol.Message.Event(RailgunEvent.Log("scanning")),
            RailgunProtocol.decode("""{"event":"log","data":"scanning"}"""),
        )
    }

    @Test
    fun dropsWhatItDoesNotUnderstand() {
        assertNull(RailgunProtocol.decode("not json"))
        assertNull(RailgunProtocol.decode("""{"event":"unknown","data":1}"""))
        assertNull(RailgunProtocol.decode("""{"event":"scan","data":{"tree":"other","status":"Updated"}}"""))
        assertNull(RailgunProtocol.decode("""{"result":1}"""))
    }

    @Test
    fun decodesBalancesByBucket() {
        val balances =
            RailgunProtocol.decodeBalances(
                Json.parseToJsonElement(
                    """
                    {
                      "Spendable": [{"token": "0x1c7d", "amount": "123456789012345678901234567890"}],
                      "ShieldPending": [],
                      "SomeNewBucket": [{"token": "0x1", "amount": "1"}]
                    }
                    """.trimIndent()
                )
            )
        assertEquals(
            RailgunBalances(
                mapOf(
                    RailgunBalanceBucket.SPENDABLE to
                        listOf(RailgunTokenAmount("0x1c7d", BigInteger("123456789012345678901234567890"))),
                    RailgunBalanceBucket.SHIELD_PENDING to emptyList(),
                )
            ),
            balances,
        )
    }
}
