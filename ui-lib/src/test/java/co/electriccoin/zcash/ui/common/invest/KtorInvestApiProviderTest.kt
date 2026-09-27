package co.electriccoin.zcash.ui.common.invest

import cash.z.ecc.android.sdk.exception.TorUnavailableException
import co.electriccoin.zcash.ui.common.invest.model.AuthenticateRequest
import co.electriccoin.zcash.ui.common.invest.model.Erc191SignedData
import co.electriccoin.zcash.ui.common.invest.model.GenerateIntentRequest
import co.electriccoin.zcash.ui.common.invest.model.SubmitIntentRequest
import co.electriccoin.zcash.ui.common.invest.provider.InvestApiException
import co.electriccoin.zcash.ui.common.invest.provider.InvestServerClock
import co.electriccoin.zcash.ui.common.invest.provider.KtorInvestApiProvider
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Fixtures are real 1Click bodies, kept on one line to match what the wire carries.
@Suppress("MaxLineLength")
class KtorInvestApiProviderTest {
    private val signed = Erc191SignedData("erc191", "{\"x\":1}", "secp256k1:abc")

    @Test
    fun `sign-in sends only the signed data, with no partner token`() =
        runTest {
            val http =
                FakeHttpClientProvider {
                    jsonResponse("""{"accessToken":"a.b.c","expiresIn":900,"refreshToken":"r","refreshExpiresIn":604800}""")
                }
            val api = KtorInvestApiProvider(http, InvestServerClock())

            val response = api.authenticate(AuthenticateRequest(signed))

            val request = http.requests.single()
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("https://1click.chaindefuser.com/v0/auth/authenticate", request.url.toString())
            assertNull(request.headers[HttpHeaders.Authorization])
            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            val signedData = body.getValue("signedData").jsonObject
            assertEquals("erc191", signedData.getValue("standard").jsonPrimitive.content)
            assertEquals("{\"x\":1}", signedData.getValue("payload").jsonPrimitive.content)
            assertEquals("secp256k1:abc", signedData.getValue("signature").jsonPrimitive.content)
            assertEquals("a.b.c", response.accessToken)
            assertEquals(900, response.expiresIn)
        }

    @Test
    fun `balances carry the session token, intents carry the partner token`() =
        runTest {
            val http =
                FakeHttpClientProvider { request ->
                    when (request.url.encodedPath) {
                        "/v0/account/balances" -> {
                            jsonResponse("""{"balances":[{"tokenId":"nep141:x","available":"10","source":"private"}]}""")
                        }

                        "/v0/generate-intent" -> {
                            jsonResponse("""{"intent":{"standard":"erc191","payload":"{}"},"correlationId":"c1"}""")
                        }

                        else -> {
                            jsonResponse("""{"intentHash":"h","correlationId":"c2"}""")
                        }
                    }
                }
            val api = KtorInvestApiProvider(http, InvestServerClock())

            val balances = api.getBalances("session-token")
            api.generateIntent(GenerateIntentRequest(standard = "erc191", signerId = TEST_ACCOUNT_ID, depositAddress = "d"))
            api.submitIntent(SubmitIntentRequest(signedData = Erc191SignedData("erc191", "{}", "secp256k1:s")))

            val (balanceRequest, generate, submit) = http.requests
            assertEquals("Bearer session-token", balanceRequest.headers[HttpHeaders.Authorization])
            assertEquals("10", balances.balances.single().available)
            listOf(generate, submit).forEach {
                val auth = it.headers[HttpHeaders.Authorization].orEmpty()
                assertTrue(auth.startsWith("Bearer ey") && auth != "Bearer session-token", it.url.encodedPath)
            }
            val generateBody = Json.parseToJsonElement((generate.body as TextContent).text).jsonObject
            assertEquals("swap_transfer", generateBody.getValue("type").jsonPrimitive.content)
        }

    @Test
    fun `the Ondo list asks for ondoTokens`() =
        runTest {
            val http = FakeHttpClientProvider { jsonResponse("[]") }

            KtorInvestApiProvider(http, InvestServerClock()).getOndoTokens()

            assertEquals(
                "true",
                http.requests
                    .single()
                    .url.parameters["ondoTokens"]
            )
        }

    @Test
    fun `login and intent calls are never retried, reads are`() =
        runTest {
            val http = FakeHttpClientProvider { jsonResponse("""{"message":"busy"}""", HttpStatusCode.ServiceUnavailable) }
            val api = KtorInvestApiProvider(http, InvestServerClock())

            assertFailsWith<InvestApiException.Api> { api.authenticate(AuthenticateRequest(signed)) }
            assertFailsWith<InvestApiException.Api> {
                api.generateIntent(GenerateIntentRequest(standard = "erc191", signerId = TEST_ACCOUNT_ID, depositAddress = "d"))
            }
            assertFailsWith<InvestApiException.Api> { api.submitIntent(SubmitIntentRequest(signedData = signed)) }
            assertEquals(3, http.requests.size)

            http.requests.clear()
            assertFailsWith<InvestApiException.Api> { api.getOndoTokens() }
            assertEquals(1 + FakeHttpClientProvider.RETRIES, http.requests.size)
        }

    @Test
    fun `1Click errors map to what the UI needs, keeping the correlation id`() =
        runTest {
            suspend fun failWith(
                status: HttpStatusCode,
                body: String,
                login: Boolean = false,
            ): InvestApiException {
                val api = KtorInvestApiProvider(FakeHttpClientProvider { jsonResponse(body, status) }, InvestServerClock())
                return assertFailsWith<InvestApiException> {
                    if (login) api.authenticate(AuthenticateRequest(signed)) else api.getOndoTokens()
                }
            }

            val noPrice = failWith(HttpStatusCode.BadRequest, """{"message":"No liquidity available","correlationId":"cid-1"}""")
            assertIs<InvestApiException.NoPrice>(noPrice)
            assertEquals("cid-1", noPrice.correlationId)

            val skew =
                failWith(
                    HttpStatusCode.Unauthorized,
                    """{"message":"timestamp validation failed","error":"Unauthorized","statusCode":401}""",
                    login = true,
                )
            assertIs<InvestApiException.ClockSkew>(skew)

            val refused = failWith(HttpStatusCode.Unauthorized, """{"message":"signature verification failed"}""", login = true)
            assertIs<InvestApiException.LoginRefused>(refused)
            assertEquals("signature verification failed", refused.apiMessage)

            assertIs<InvestApiException.Unauthorized>(
                failWith(HttpStatusCode.Unauthorized, """{"message":"Authorization token required"}"""),
            )

            val other = failWith(HttpStatusCode.BadRequest, """{"message":"Quote error. INSUFFICIENT_AMOUNT","correlationId":"cid-2"}""")
            assertTrue(other is InvestApiException.Api && other.status == 400 && other.correlationId == "cid-2")

            val unreadable = failWith(HttpStatusCode.InternalServerError, "<html>")
            assertTrue(unreadable is InvestApiException.Api && unreadable.status == 500)
        }

    @Test
    fun `Tor that can't start is TorUnavailable, not a network error`() =
        runTest {
            val api = KtorInvestApiProvider(FakeHttpClientProvider(TorUnavailableException()) { error("unreached") }, InvestServerClock())

            assertFailsWith<InvestApiException.TorUnavailable> { api.getOndoTokens() }
        }

    @Test
    fun `transport failures become Unreachable`() =
        runTest {
            val api = KtorInvestApiProvider(FakeHttpClientProvider { throw IOException("circuit closed") }, InvestServerClock())

            assertFailsWith<InvestApiException.Unreachable> { api.getOndoTokens() }
        }

    @Test
    fun `an undecodable response never carries the body into the exception`() =
        runTest {
            val api =
                KtorInvestApiProvider(
                    FakeHttpClientProvider { jsonResponse("""{"accessToken":"secret-session-token","expiresIn":900.5}""") },
                    InvestServerClock(),
                )

            val failure = assertFailsWith<InvestApiException.Unreachable> { api.authenticate(AuthenticateRequest(signed)) }

            val text = generateSequence<Throwable>(failure) { it.cause }.joinToString { "${it.message} ${it.stackTraceToString()}" }
            assertFalse("secret-session-token" in text)
        }

    @Test
    fun `every 1Click response corrects the clock, errors included`() =
        runTest {
            val clock = InvestServerClock(deviceNowMillis = { 1_790_604_000_000L }) // 2026-09-28T14:00:00Z
            val api =
                KtorInvestApiProvider(
                    FakeHttpClientProvider {
                        jsonResponse("""{"message":"No liquidity available"}""", HttpStatusCode.BadRequest, "Mon, 28 Sep 2026 14:05:00 GMT")
                    },
                    clock,
                )

            assertFailsWith<InvestApiException.NoPrice> { api.getOndoTokens() }

            assertEquals(1_790_604_300_000L, clock.nowMillis())
        }
}
