// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.p2p

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SubgraphClientTest {
    private var nextResponse: String = ""
    private val sentBodies = mutableListOf<JsonObject>()

    private val client =
        HttpClient(
            MockEngine { request ->
                val bytes = (request.body as io.ktor.http.content.OutgoingContent.ByteArrayContent).bytes()
                sentBodies += Json.parseToJsonElement(bytes.decodeToString()) as JsonObject
                respond(nextResponse, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        ) {
            install(ContentNegotiation) { json() }
        }

    private val subgraph = SubgraphClient(client, "http://mock/subgraph")

    @AfterTest
    fun tearDown() {
        client.close()
    }

    @Test
    fun `user order history matches the recipient as well as the placer`() =
        runTest {
            nextResponse = """{"data":{"orders_collection":[]}}"""

            subgraph.ordersForUser(USER, first = 10, skip = 0)

            val query = sentBodies.last()["query"]!!.jsonPrimitive.content
            // An onramp BUY is placed on-chain by the operator, so userAddress is theirs and only
            // usdcRecipientAddress is the user's. Filtering on the placer alone hid every purchase.
            assertTrue(query.contains("usdcRecipientAddress: ${'$'}userAddress"), "must match on recipient: $query")
            assertTrue(query.contains("userAddress: ${'$'}userAddress"), "must still match on placer: $query")
            assertTrue(query.contains("or:"), "both must be an OR, not an AND: $query")
        }

    @Test
    fun `the address is lower-cased, since the subgraph stores bytes that way`() =
        runTest {
            nextResponse = """{"data":{"orders_collection":[]}}"""

            subgraph.ordersForUser("0x4A96C8EB7ECB6ECFE5855BB96A0EA379E339FA44", first = 10, skip = 0)

            val vars = sentBodies.last()["variables"]!!.jsonObject
            assertEquals("0x4a96c8eb7ecb6ecfe5855bb96a0ea379e339fa44", vars["userAddress"]!!.jsonPrimitive.content)
        }

    @Test
    fun `parses circles and drops zero-active-merchant entries`() =
        runTest {
            nextResponse =
                """
                {"data":{"circles":[
                  {"circleId":"1","currency":"0x494e520000000000000000000000000000000000000000000000000000000000",
                    "metrics":{"circleScore":"12.5","circleStatus":"active",
                      "scoreState":{"activeMerchantsCount":"4"}}},
                  {"circleId":"2","currency":"0x494e520000000000000000000000000000000000000000000000000000000000",
                    "metrics":{"circleScore":"3.0","circleStatus":"bootstrap",
                      "scoreState":{"activeMerchantsCount":"0"}}}
                ]}}
                """.trimIndent()
            val circles =
                subgraph.circlesForRouting(
                    "0x494e520000000000000000000000000000000000000000000000000000000000",
                )
            assertEquals(1, circles.size)
            assertEquals("1", circles[0].circleId)
            assertEquals("active", circles[0].metrics.circleStatus)
        }

    @Test
    fun `sends query and variables in POST body`() =
        runTest {
            nextResponse = """{"data":{"circles":[]}}"""
            subgraph.circlesForRouting("0xabcd")
            val body = sentBodies.last()
            assertTrue(body["query"]!!.jsonPrimitive.content.contains("CirclesForRouting"))
            assertEquals("0xabcd", body["variables"]!!.jsonObject["currency"]!!.jsonPrimitive.content)
        }

    @Test
    fun `surfaces graphql errors`() =
        runTest {
            nextResponse = """{"errors":[{"message":"boom"}]}"""
            assertFailsWith<IllegalStateException> {
                subgraph.circlesForRouting("0xabcd")
            }
        }

    @Test
    fun `empty circles array returns empty list`() =
        runTest {
            nextResponse = """{"data":{"circles":[]}}"""
            assertEquals(0, subgraph.circlesForRouting("0xabcd").size)
        }

    private companion object {
        const val USER = "0x4a96c8eb7ecb6ecfe5855bb96a0ea379e339fa44"
    }
}
