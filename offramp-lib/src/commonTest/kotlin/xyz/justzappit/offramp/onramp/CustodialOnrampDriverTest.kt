// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.onramp

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import xyz.justzappit.evm.hd.EvmKeyDerivation
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.util.hexToBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CustodialOnrampDriverTest {
    @Test
    fun `start walks placing through awaiting payment and stops for the user`() =
        runTest {
            val driver =
                driverFor(
                    order(PHASE_PLACING),
                    order(PHASE_MERCHANT),
                    order(PHASE_PAYMENT, withInstruction = true),
                )

            val statuses = driver.start(quote()).toList()

            // Placing twice: once before the POST, once for the phase the service reports back.
            assertIs<OnrampStatus.Placing>(statuses[0])
            assertIs<OnrampStatus.Placing>(statuses[1])
            assertIs<OnrampStatus.AwaitingMerchant>(statuses[2])
            val awaiting = assertIs<OnrampStatus.AwaitingPayment>(statuses[3])
            assertEquals(ORDER_ID, awaiting.orderId)
            assertIs<OnrampPaymentInstruction.Upi>(awaiting.instruction)
            // Polling must stop here: the next move belongs to the user, not the service.
            assertEquals(4, statuses.size)
        }

    @Test
    fun `the intent url is passed through untouched`() =
        runTest {
            val driver = driverFor(order(PHASE_PAYMENT, withInstruction = true))

            val awaiting = assertIs<OnrampStatus.AwaitingPayment>(driver.start(quote()).toList().last())

            assertEquals(INTENT_URL, assertIs<OnrampPaymentInstruction.Upi>(awaiting.instruction).intentUrl)
        }

    @Test
    fun `settlement polls on to completion`() =
        runTest {
            val driver = driverFor(order(PHASE_SETTLEMENT), order(PHASE_COMPLETED))

            val statuses = driver.confirmPaid(checkpoint()).toList()

            assertIs<OnrampStatus.ConfirmingPaid>(statuses.first())
            val completed = assertIs<OnrampStatus.Completed>(statuses.last())
            assertEquals("910153", completed.netUsdc.micros.toString())
            assertEquals(SMART_ACCOUNT.lowercase(), completed.recipientAddress.lowercaseHex)
        }

    @Test
    fun `completion without the echoed recipient fails closed`() =
        runTest {
            val driver = driverFor(order(PHASE_COMPLETED, withRecipient = false))

            val failed = assertIs<OnrampStatus.Failed>(driver.resume(checkpoint()).toList().last())

            assertEquals(OnrampFailureCode.UPSTREAM_FAILED, failed.code)
        }

    @Test
    fun `an expired order surfaces its failure code and terminates`() =
        runTest {
            val driver = driverFor(order(PHASE_EXPIRED, failureCode = "NO_MERCHANT"))

            val failed = assertIs<OnrampStatus.Failed>(driver.resume(checkpoint()).toList().last())

            assertEquals(OnrampFailureCode.NO_MERCHANT, failed.code)
            assertTrue(!failed.leavesOrderAlive)
        }

    @Test
    fun `awaiting payment without an instruction fails rather than showing an empty screen`() =
        runTest {
            val driver = driverFor(order(PHASE_PAYMENT, withInstruction = false))

            val failed = assertIs<OnrampStatus.Failed>(driver.resume(checkpoint()).toList().last())

            assertEquals(OnrampFailureCode.UPSTREAM_FAILED, failed.code)
        }

    @Test
    fun `a typed service error becomes a failed status instead of throwing`() =
        runTest {
            val driver =
                driverFor(errorBody = """{"code":"CAP_EXCEEDED","message":"over the daily cap"}""", status = 429)

            val failed = assertIs<OnrampStatus.Failed>(driver.resume(checkpoint()).toList().last())

            assertEquals(OnrampFailureCode.CAP_EXCEEDED, failed.code)
        }

    @Test
    fun `every signed call carries the four auth headers and a fresh nonce`() =
        runTest {
            val seen = mutableListOf<HttpRequestData>()
            val driver = driverFor(order(PHASE_COMPLETED), record = seen)

            driver.resume(checkpoint()).toList()

            val signed = seen.filter { !it.url.encodedPath.endsWith("/v1/config") }
            assertTrue(signed.isNotEmpty())
            signed.forEach { request ->
                assertEquals("zapp", request.headers["x-p2p-app"])
                assertEquals(SIGNER_ADDRESS, request.headers["x-p2p-address"])
                assertEquals(NONCE, request.headers["x-p2p-nonce"])
                assertEquals(SIGNATURE_HEX_LEN, request.headers["x-p2p-signature"]?.removePrefix("0x")?.length)
            }
            // One /v1/config per signed call: nonces are single use.
            assertEquals(signed.size, seen.count { it.url.encodedPath.endsWith("/v1/config") })
        }

    /**
     * The service 500s when `doNotTrack` is absent but accepts it as an explicit null, and 500s on a
     * null `seonSession` where an absent one is fine. Both were verified against the running
     * service; encoding them the other way round is an opaque INTERNAL error at order placement.
     */
    @Test
    fun `device encoding sends doNotTrack as null and omits an absent seonSession`() =
        runTest {
            val seen = mutableListOf<HttpRequestData>()
            val driver = driverFor(order(PHASE_COMPLETED), record = seen)

            driver.start(quote()).toList()

            val posted = seen.first { it.url.encodedPath.endsWith("/v1/orders") }
            val body = posted.body.toByteArray().decodeToString()
            assertTrue(body.contains(""""doNotTrack":null"""), "doNotTrack must be an explicit null: $body")
            assertTrue(!body.contains("seonSession"), "an absent seonSession must be omitted: $body")
            // Signals that are present must still be sent.
            assertTrue(body.contains(""""hardwareConcurrency":8"""), body)
            assertTrue(body.contains(""""timezoneOffset":-330"""), body)
        }

    @Test
    fun `a service stuck in a non-terminal phase gives up instead of polling forever`() =
        runTest {
            val driver = driverFor(order(PHASE_MERCHANT))

            val statuses = driver.resume(checkpoint()).toList()

            val failed = assertIs<OnrampStatus.Failed>(statuses.last())
            assertEquals(OnrampFailureCode.OPERATOR_UNAVAILABLE, failed.code)
            // Transient, so the checkpoint survives and reopening the screen resumes the order.
            assertTrue(failed.leavesOrderAlive)
        }

    @Test
    fun `cancel reports cancelled without polling on`() =
        runTest {
            val driver = driverFor(order(PHASE_CANCELLED))

            assertIs<OnrampStatus.Cancelled>(driver.cancel(checkpoint()).toList().single())
        }

    /** The corridor is the user's choice, so it must travel with the request, not the driver. */
    @Test
    fun `a quote is requested in the corridor it was asked for, not the driver default`() =
        runTest {
            val seen = mutableListOf<HttpRequestData>()
            val engine =
                MockEngine { request ->
                    seen.add(request)
                    if (request.url.encodedPath.endsWith("/v1/config")) {
                        respond(configBodyFor("INR"), HttpStatusCode.OK, jsonHeaders)
                    } else {
                        respond(QUOTE_BODY, HttpStatusCode.OK, jsonHeaders)
                    }
                }
            val driver =
                CustodialOnrampDriver(
                    client =
                        CustodialOnrampClient(
                            httpClient = HttpClient(engine),
                            baseUrl = "https://onramp.example",
                            signerProvider = { signer },
                        ),
                    deviceSignals = { DEVICE },
                    recipientProvider = { Address.parse(SMART_ACCOUNT) },
                    fallbackCurrency = xyz.justzappit.offramp.p2p.CurrencyCode.Inr,
                )

            val quoted =
                driver.quote(
                    xyz.justzappit.offramp.p2p.Usdc6
                        .ofMicros(25_000_000),
                    xyz.justzappit.offramp.p2p.CurrencyCode.Brl,
                )

            val body =
                seen
                    .first { it.url.encodedPath.endsWith("/v1/quote") }
                    .body
                    .toByteArray()
                    .decodeToString()
            assertTrue(body.contains(""""currency":"BRL""""), "the chosen corridor must be sent: $body")
            assertEquals(xyz.justzappit.offramp.p2p.CurrencyCode.Brl, quoted.currency)
        }

    @Test
    fun `limits ask the service for the corridor the caller chose`() =
        runTest {
            val seen = mutableListOf<HttpRequestData>()
            val driver = driverFor(order(PHASE_PLACING), record = seen)

            val limits = driver.limits(xyz.justzappit.offramp.p2p.CurrencyCode.Brl)

            val config = seen.first { it.url.encodedPath.endsWith("/v1/config") }
            assertEquals("BRL", config.url.parameters["currency"], "the corridor must travel with the request")
            assertEquals(xyz.justzappit.offramp.p2p.CurrencyCode.Brl, limits.currency)
            // BRL's caps, not the default corridor's. Asking for none returns INR's, which are
            // twentyfold larger and would let an order through at twenty times the intended size.
            assertEquals("5160000", limits.minFiat.micros.toString())
            assertEquals("103200000", limits.maxFiat.micros.toString())
        }

    @Test
    fun `a corridor this build cannot render is disabled, not relabelled as rupees`() {
        // MEX is live on the service and absent from CurrencyCode. Defaulting it to INR would
        // put Mexican caps and Mexican amounts under a rupee sign.
        val limits =
            OnrampConfigDto(
                nonce = NONCE,
                enabled = true,
                currency = "MEX",
                minFiat = "100000000",
                maxFiat = "500000000",
                perUserDailyFiat = "1000000000",
            ).toLimits()

        assertEquals(false, limits.enabled)
    }

    @Test
    fun `an order named in a currency this build cannot render fails rather than guessing`() {
        val thrown =
            runCatching {
                OnrampOrderDto(
                    id = ID,
                    orderId = ORDER_ID,
                    phase = PHASE_PAYMENT,
                    currency = "MEX",
                    fiatAmount = "99999934",
                    netUsdc = "910153",
                ).toOrder(xyz.justzappit.offramp.p2p.CurrencyCode.Inr)
            }.exceptionOrNull()

        assertEquals(OnrampFailureCode.UPSTREAM_FAILED, assertIs<OnrampException>(thrown).code)
    }

    @Test
    fun `usdc settles to the smart account, never to the key that signed the request`() =
        runTest {
            val seen = mutableListOf<HttpRequestData>()
            val driver = driverFor(order(PHASE_PLACING), record = seen)

            driver.start(quote()).toList()

            val body =
                seen
                    .first { it.url.encodedPath.endsWith("/v1/orders") }
                    .body
                    .toByteArray()
                    .decodeToString()
            // The service derives this address from the signer and refuses anything else, so
            // sending the signer's own address fails every order closed.
            assertTrue(body.contains(SMART_ACCOUNT, ignoreCase = true), "recipient must be the smart account: $body")
            assertFalse(body.contains(SIGNER_ADDRESS, ignoreCase = true), "the EOA must not be the recipient: $body")
        }

    @Test
    fun `the address offered as the destination is the one that will receive`() =
        runTest {
            val driver = driverFor(order(PHASE_PLACING))

            assertEquals(SMART_ACCOUNT.lowercase(), driver.recipientAddress().checksumHex.lowercase())
        }

    private fun driverFor(
        vararg orderBodies: String,
        errorBody: String? = null,
        status: Int = 200,
        record: MutableList<HttpRequestData>? = null,
    ): CustodialOnrampDriver {
        var index = 0
        val engine =
            MockEngine { request ->
                record?.add(request)
                when {
                    request.url.encodedPath.endsWith("/v1/config") -> {
                        // Mirrors the live service: it answers for whichever corridor is asked
                        // for, and for its own default when asked for none.
                        respond(
                            configBodyFor(request.url.parameters["currency"] ?: "INR"),
                            HttpStatusCode.OK,
                            jsonHeaders,
                        )
                    }

                    errorBody != null -> {
                        respond(errorBody, HttpStatusCode.fromValue(status), jsonHeaders)
                    }

                    else -> {
                        respond(
                            orderBodies[index.coerceAtMost(orderBodies.lastIndex)].also { index++ },
                            HttpStatusCode.OK,
                            jsonHeaders,
                        )
                    }
                }
            }
        return CustodialOnrampDriver(
            client =
                CustodialOnrampClient(
                    httpClient = HttpClient(engine),
                    baseUrl = "https://onramp.example",
                    signerProvider = { signer },
                ),
            deviceSignals = { DEVICE },
            recipientProvider = { Address.parse(SMART_ACCOUNT) },
            paymentPollMillis = 0,
            settlementPollMillis = 0,
            maxPolls = 5,
        )
    }

    private fun quote(): OnrampQuote =
        OnrampQuote(
            quoteId = "q",
            currency = xyz.justzappit.offramp.p2p.CurrencyCode.Inr,
            fiatAmount =
                xyz.justzappit.offramp.p2p.Usdc6
                    .ofMicros(99_999_934),
            grossUsdc =
                xyz.justzappit.offramp.p2p.Usdc6
                    .ofMicros(960_153),
            feeUsdc =
                xyz.justzappit.offramp.p2p.Usdc6
                    .ofMicros(50_000),
            netUsdc =
                xyz.justzappit.offramp.p2p.Usdc6
                    .ofMicros(910_153),
            buyPrice =
                xyz.justzappit.offramp.p2p.Usdc6
                    .ofMicros(104_150_000),
            expiresAtMillis = 0,
        )

    private fun checkpoint(): OnrampCheckpoint =
        OnrampCheckpoint(
            id = ID,
            phase = OnrampPhase.AWAITING_PAYMENT,
            orderId = ORDER_ID,
        )

    private fun order(
        phase: String,
        withInstruction: Boolean = false,
        failureCode: String? = null,
        withRecipient: Boolean = true,
    ): String {
        val instruction =
            if (withInstruction) {
                ""","paymentInstruction":{"kind":"upi","address":"merchant@upi",""" +
                    """"intentUrl":"$INTENT_URL","amount":"100.00"}"""
            } else {
                ""
            }
        val failure = failureCode?.let { ""","failureCode":"$it"""" }.orEmpty()
        val recipient = if (withRecipient) ""","recipientAddr":"$SMART_ACCOUNT"""" else ""
        return """{"id":"$ID","orderId":"$ORDER_ID","phase":"$phase","currency":"INR",""" +
            """"fiatAmount":"99999934","netUsdc":"910153"$instruction$failure$recipient}"""
    }

    private companion object {
        const val ID = "00000000-0000-4000-8000-000000000000"
        const val ORDER_ID = "659007"
        const val NONCE = "11111111-1111-4111-8111-111111111111"
        const val SIGNER_ADDRESS = "0x2c7536E3605D9C16a7a3D7b1898e529396a65c23"

        // Thirdweb's CREATE2 account for SIGNER_ADDRESS. A different address entirely, which is
        // the whole point: the two are not interchangeable.
        const val SMART_ACCOUNT = "0x6eC58396952C4Ea2Ee0Ff0eFCB0Cc0Ec0E0f0A0b"
        const val SIGNATURE_HEX_LEN = 130
        const val INTENT_URL = "upi://pay?pa=merchant@upi&pn=Merchant&am=100.00&cu=INR&tr=659007"
        const val PHASE_PLACING = "placing"
        const val PHASE_MERCHANT = "awaiting_merchant"
        const val PHASE_PAYMENT = "awaiting_payment"
        const val PHASE_SETTLEMENT = "awaiting_settlement"
        const val PHASE_COMPLETED = "completed"
        const val PHASE_CANCELLED = "cancelled"
        const val PHASE_EXPIRED = "expired"
        const val QUOTE_BODY =
            """{"quoteId":"q","currency":"BRL","fiatAmount":"25000000","grossUsdc":"4800000",""" +
                """"feeUsdc":"50000","netUsdc":"4750000","buyPrice":"5260000","expiresAt":0}"""

        // Caps come off each corridor's own live buy price, so BRL's are a different number
        // from INR's rather than the same number in another symbol. Values are the live
        // service's, read from /v1/config on 2026-08-07.
        fun configBodyFor(currency: String): String {
            val caps =
                when (currency) {
                    "BRL" -> Triple("5160000", "103200000", "258000000")
                    else -> Triple("104260000", "2085200000", "5213000000")
                }
            return """{"nonce":"$NONCE","enabled":true,"currency":"$currency",""" +
                """"minFiat":"${caps.first}","maxFiat":"${caps.second}",""" +
                """"perUserDailyFiat":"${caps.third}","chainId":8453}"""
        }

        val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

        val signer =
            OnrampRequestSigner(
                EvmKeyDerivation.fromPrivateKey(
                    "0x4c0883a69102937d6231471b5dbb6204fe5129617082792ae468d01a3f362318".hexToBytes(),
                ),
            )

        val DEVICE =
            OnrampDeviceSignals(
                userAgent = "test",
                platform = "Android",
                language = "en-IN",
                languages = listOf("en-IN"),
                screenWidth = 1080,
                screenHeight = 2400,
                devicePixelRatio = 2.75,
                timezone = "Asia/Kolkata",
                timezoneOffset = -330,
                cookiesEnabled = true,
                doNotTrack = null,
                online = true,
                touchSupport = true,
                maxTouchPoints = 5,
                vendor = "test",
                appVersion = "1.0.0",
                colorDepth = 24,
                pixelDepth = 24,
                connectionType = "wifi",
                deviceMemory = 8.0,
                hardwareConcurrency = 8,
            )
    }
}
