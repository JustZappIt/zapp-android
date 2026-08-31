// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.peer

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import xyz.justzappit.evm.math.bigIntegerOne
import xyz.justzappit.evm.types.Address
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PeerIndexerFailureTest {
    @Test
    fun `missing Deposit field is an outage rather than empty history`() =
        runTest {
            client("""{"data":{}}""").use { fixture ->
                val failure =
                    assertFailsWith<PeerException> {
                        fixture.indexer.allOrdersFor(ACCOUNT)
                    }

                assertEquals(PeerErrorCode.INDEXER_UNAVAILABLE, failure.error.code)
            }
        }

    @Test
    fun `unknown deposit status is rejected rather than synthesized as closed`() =
        runTest {
            val id = PeerDepositId.of(ESCROW, bigIntegerOne)
            val response = """{"data":{"Deposit":[{"id":"${id.composite}","status":"ALIEN"}]}}"""
            client(response).use { fixture ->
                val failure =
                    assertFailsWith<PeerException> {
                        fixture.indexer.order(id)
                    }

                assertEquals(PeerErrorCode.INDEXER_UNAVAILABLE, failure.error.code)
            }
        }

    private fun client(response: String): Fixture {
        val http =
            HttpClient(
                MockEngine {
                    respond(
                        response,
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                },
            ) {
                install(ContentNegotiation) { json() }
            }
        return Fixture(http, PeerIndexerClient(http, "http://mock/indexer"))
    }

    private class Fixture(
        private val http: HttpClient,
        val indexer: PeerIndexerClient,
    ) : AutoCloseable {
        override fun close() = http.close()
    }

    companion object {
        private val ACCOUNT = Address.parse("0x" + "11".repeat(20))
        private val ESCROW = Address.parse("0x" + "22".repeat(20))
    }
}
