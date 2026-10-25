// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.deposits

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import xyz.justzappit.evm.types.Address
import xyz.justzappit.offramp.atomicswap.SwapErrorCode
import xyz.justzappit.offramp.atomicswap.SwapNote
import xyz.justzappit.offramp.p2p.Usdc6
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class DepositsClientTest {
    @Test
    fun `a shield posts exactly its fields, in order, as JSON, with no ticket`() =
        runTest {
            val relayer = Relayer { respond("""{"transactions":["$TX"]}""") }

            assertEquals(DepositAnswer.Sent(listOf(tx(0x5a))), relayer.client.shield(SHIELD))

            assertEquals(listOf("https://host/relayer/v1/deposits/shield"), relayer.urls)
            assertEquals(
                """{"chainId":11155111,"contract":"0x58324e9a866c9690aca08169d3c67630e84c9019",""" +
                    """"from":"0x7e5f4552091a69125d5dfcb7b8c2659029395bdf","value":"25000000","validAfter":0,""" +
                    """"validBefore":1790000000,"note":{"npk":"$WORD","encryptedBundle":["$WORD","$WORD","$WORD"],""" +
                    """"shieldKey":"$WORD"},"fee":"125000","signature":"$SIGNATURE"}""",
                relayer.bodies.single(),
            )
            assertTrue(
                relayer.contentTypes
                    .single()
                    .orEmpty()
                    .startsWith("application/json")
            )
            assertEquals(listOf<String?>(null), relayer.tickets)
            relayer.close()
        }

    @Test
    fun `a rescue posts exactly its fields to its own route`() =
        runTest {
            val relayer = Relayer { respond("""{"transactions":["$TX"]}""") }

            assertEquals(DepositAnswer.Sent(listOf(tx(0x5a))), relayer.client.rescue(RESCUE))

            assertEquals(listOf("https://host/relayer/v1/deposits/rescue"), relayer.urls)
            assertEquals(
                """{"chainId":11155111,"contract":"0x58324e9a866c9690aca08169d3c67630e84c9019",""" +
                    """"from":"0x7e5f4552091a69125d5dfcb7b8c2659029395bdf","note":{"npk":"$WORD",""" +
                    """"encryptedBundle":["$WORD","$WORD","$WORD"],"shieldKey":"$WORD"},"fee":"100000",""" +
                    """"nonce":3,"deadline":1790000000,"signature":"$SIGNATURE"}""",
                relayer.bodies.single(),
            )
            relayer.close()
        }

    @Test
    fun `a 200 naming nothing, or that doesn't read, is no answer`() =
        runTest {
            var body = """{"transactions":[]}"""
            val relayer = Relayer { respond(body) }

            assertEquals(
                DepositAnswer.Retry("the relayer named no transaction", null, isWaiting = false),
                relayer.client.shield(SHIELD),
            )
            body = "<html>"
            val unreadable = assertIs<DepositAnswer.Retry>(relayer.client.shield(SHIELD))
            assertTrue(unreadable.isWaiting)
            relayer.close()
        }

    @Test
    fun `400, 413, 415 and 422 refuse it, with the relayer's reason`() =
        runTest {
            var status = HttpStatusCode.BadRequest
            var body = """{"code":"rejected","error":"the deposit address holds less than it authorizes"}"""
            val relayer = Relayer { respond(body, status) }

            val rejected = assertIs<DepositAnswer.Refused>(relayer.client.shield(SHIELD))
            assertEquals(400, rejected.status)
            assertTrue("holds less than it authorizes" in rejected.reason)
            for (code in listOf(HttpStatusCode.UnsupportedMediaType, HttpStatusCode.UnprocessableEntity)) {
                status = code
                body = """{"code":"invalidRequest","error":"not this request"}"""
                assertEquals(code.value, assertIs<DepositAnswer.Refused>(relayer.client.shield(SHIELD)).status)
            }
            // The gateway's, for a body over 16 KiB: no JSON.
            status = HttpStatusCode.PayloadTooLarge
            body = "Payload Too Large"
            assertEquals(413, assertIs<DepositAnswer.Refused>(relayer.client.rescue(RESCUE)).status)
            relayer.close()
        }

    @Test
    fun `a 409 names the relayer's own transactions, or none`() =
        runTest {
            var body = """{"code":"conflict","error":"another deposit is on its way","transactions":["$TX"]}"""
            val relayer = Relayer { respond(body, HttpStatusCode.Conflict) }

            assertEquals(DepositAnswer.Conflict(listOf(tx(0x5a))), relayer.client.shield(SHIELD))
            body = """{"code":"conflict","error":"the authorization is used","transactions":[]}"""
            assertEquals(DepositAnswer.Conflict(emptyList()), relayer.client.shield(SHIELD))
            assertEquals(SwapErrorCode.CONFLICT, SwapErrorCode.named("conflict"))
            relayer.close()
        }

    @Test
    fun `a 503 asking 30 s or less is posted again, the same bytes, for up to 90 s in all`() =
        runTest {
            var busy = 2
            val relayer =
                Relayer {
                    if (busy-- > 0) {
                        val busyAnswer = """{"code":"unavailable","error":"busy"}"""
                        respond(busyAnswer, HttpStatusCode.ServiceUnavailable, retry(5))
                    } else {
                        respond("""{"transactions":["$TX"]}""")
                    }
                }

            assertEquals(DepositAnswer.Sent(listOf(tx(0x5a))), relayer.client.shield(SHIELD))
            assertEquals(3, relayer.bodies.size)
            assertEquals(1, relayer.bodies.toSet().size, "the same bytes")
            assertEquals(10_000L, currentTime)
            relayer.close()
        }

    @Test
    fun `a 503 that keeps asking is left to the next step once 90 s are waited out`() =
        runTest {
            val relayer = Relayer { respond("""{"error":"busy"}""", HttpStatusCode.ServiceUnavailable, retry(30)) }

            val retry = assertIs<DepositAnswer.Retry>(relayer.client.shield(SHIELD))

            assertEquals(30.seconds, retry.after)
            assertTrue(retry.isWaiting)
            assertEquals(4, relayer.bodies.size)
            assertEquals(90_000L, currentTime)
            relayer.close()
        }

    @Test
    fun `a 503 asking longer, a 500 and anything else are waited for, with what the relayer asked`() =
        runTest {
            var status = HttpStatusCode.ServiceUnavailable
            var after: String? = "300"
            val relayer =
                Relayer {
                    respond("""{"error":"later"}""", status, after?.let { retry(it) } ?: headersOf())
                }

            assertEquals(
                DepositAnswer.Retry("relayer answered 503: later", 5.minutes, isWaiting = true),
                relayer.client.shield(SHIELD),
            )
            assertEquals(1, relayer.bodies.size)
            after = "86400"
            assertEquals(60.minutes, assertIs<DepositAnswer.Retry>(relayer.client.shield(SHIELD)).after)
            after = null
            val others =
                listOf(HttpStatusCode.InternalServerError, HttpStatusCode.NotFound, HttpStatusCode.TooManyRequests)
            for (other in others) {
                status = other
                val retry = assertIs<DepositAnswer.Retry>(relayer.client.shield(SHIELD))
                assertTrue(retry.isWaiting, other.toString())
            }
            relayer.close()
        }

    @Test
    fun `no answer is waited for, and posted again later`() =
        runTest {
            val relayer = Relayer { throw IOException("timed out") }

            val retry = assertIs<DepositAnswer.Retry>(relayer.client.shield(SHIELD))

            assertTrue(retry.isWaiting)
            assertEquals(null, retry.after)
            relayer.close()
        }

    @Test
    fun `neither request ever shows its signature or note`() {
        assertEquals("ShieldDeposit", SHIELD.toString())
        assertEquals("RescueDeposit", RESCUE.toString())
    }

    private class Relayer(
        answer: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ) {
        val urls = mutableListOf<String>()
        val bodies = mutableListOf<String>()
        val contentTypes = mutableListOf<String?>()
        val tickets = mutableListOf<String?>()
        private val http =
            HttpClient(
                MockEngine { request ->
                    urls += request.url.toString()
                    tickets += request.headers["ZecSwap-Ticket"]
                    (request.body as? TextContent)?.let {
                        bodies += it.text
                        contentTypes += it.contentType.toString()
                    }
                    answer(request)
                },
            )
        val client = DepositsClient(http, Url("https://host/relayer/"))

        fun close() = http.close()
    }

    private companion object {
        val WORD = "0x" + "22".repeat(32)
        val SIGNATURE = "0x" + "11".repeat(65)
        val TX = "0x" + "5a".repeat(32)
        val FROM = Address.parse("0x7E5F4552091A69125d5DfCb7b8C2659029395Bdf")
        val NOTE = SwapNote(WORD, List(3) { WORD }, WORD)
        val SHIELD =
            ShieldDeposit(
                chainId = SEPOLIA_PIN.chainId,
                contract = SEPOLIA_PIN.contract,
                from = FROM,
                value = Usdc6.ofMicros(25_000_000),
                validAfter = 0,
                validBefore = 1_790_000_000,
                note = NOTE,
                fee = Usdc6.ofMicros(125_000),
                signature = SIGNATURE,
            )
        val RESCUE =
            RescueDeposit(
                chainId = SEPOLIA_PIN.chainId,
                contract = SEPOLIA_PIN.contract,
                from = FROM,
                note = NOTE,
                fee = Usdc6.ofMicros(100_000),
                nonce = 3,
                deadline = 1_790_000_000,
                signature = SIGNATURE,
            )

        fun retry(seconds: Int) = headersOf(HttpHeaders.RetryAfter, seconds.toString())

        fun retry(seconds: String) = headersOf(HttpHeaders.RetryAfter, seconds)
    }
}
