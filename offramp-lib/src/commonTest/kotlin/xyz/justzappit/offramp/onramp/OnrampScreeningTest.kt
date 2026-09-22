// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.onramp

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.AES
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.justzappit.evm.hd.EvmKeyDerivation
import xyz.justzappit.evm.math.bigIntegerValueOf
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.util.hexToBytes
import xyz.justzappit.offramp.p2p.CurrencyCode
import xyz.justzappit.offramp.p2p.Usdc6
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The screening record is what merchants gate on: an order without one that matches its placer
 * routes and prices normally and is then never accepted. Three details decide whether it matches,
 * and each fails silently — the signed message's shape, the two timestamps in two different units,
 * and the encrypted payload's layout.
 */
class OnrampScreeningTest {
    private val signingKey = EvmKeyDerivation.fromPrivateKey(SIGNING_KEY_HEX.hexToBytes())
    private val signer =
        OnrampScreeningSigner(signingKey = signingKey, subject = SMART_ACCOUNT)

    private fun client(
        nowMillis: Long,
        config: OnrampScreeningConfig =
            OnrampScreeningConfig(apiUrl = "https://screening.invalid/api/v1", encryptionKeyHex = KEY_HEX),
    ) = OnrampScreeningClient(
        httpClient = HttpClient(),
        config = config,
        deviceSignals = { SIGNALS },
        nowMillis = { nowMillis },
    )

    /** A client whose screening endpoint answers with exactly [body], recording what it was sent. */
    private fun clientAnswering(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
        onLinkFailed: (String) -> Unit = {},
        onScreeningUnavailable: (String) -> Unit = {},
        record: (HttpRequestData) -> Unit = {},
    ) = OnrampScreeningClient(
        httpClient =
            HttpClient(
                MockEngine { request ->
                    record(request)
                    respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
                },
            ),
        config = OnrampScreeningConfig(apiUrl = "https://screening.invalid/api/v1", encryptionKeyHex = KEY_HEX),
        deviceSignals = { SIGNALS },
        nowMillis = { 1_756_450_000_123L },
        onLinkFailed = onLinkFailed,
        onScreeningUnavailable = onScreeningUnavailable,
    )

    /** A client whose screening endpoint cannot be reached at all. */
    private fun clientFailing(onScreeningUnavailable: (String) -> Unit) =
        OnrampScreeningClient(
            httpClient = HttpClient(MockEngine { throw IllegalStateException("connection refused") }),
            config = OnrampScreeningConfig(apiUrl = "https://screening.invalid/api/v1", encryptionKeyHex = KEY_HEX),
            deviceSignals = { SIGNALS },
            nowMillis = { 1_756_450_000_123L },
            onScreeningUnavailable = onScreeningUnavailable,
        )

    @Test
    fun `the signed headers carry seconds, and bind both addresses`() {
        val headers = client(1_756_450_000_123L).signedHeaders(signer, "activity-log")
        // ☠ Seconds here. The body and the AAD use milliseconds; mixing them decrypts to nothing.
        assertEquals("1756450000", headers["X-Timestamp"])
        // Lowercased, and the EOA — the smart account cannot produce an EIP-191 signature.
        assertEquals(signingKey.address.lowercaseHex, headers["X-Signer-Address"])
        assertTrue(headers.getValue("X-Signature").startsWith("0x"))
        assertEquals(SIGNATURE_HEX_LEN, headers.getValue("X-Signature").length)
    }

    @Test
    fun `a link-order signature differs from an activity-log one`() {
        // The action is inside the signed message, so a signature captured for one call cannot be
        // replayed against the other.
        val at = 1_756_450_000_123L
        assertNotEquals(
            client(at).signedHeaders(signer, "activity-log")["X-Signature"],
            client(at).signedHeaders(signer, "link-order")["X-Signature"],
        )
    }

    @Test
    fun `amounts are JSON numbers in whole units, at full precision`() =
        runTest {
            val payload =
                client(1_756_450_999_999L).payloadJson(
                    order = ORDER,
                    country = "India",
                    timestampMillis = 1_756_450_000_000L,
                )
            val body = Json.parseToJsonElement(payload).jsonObject
            val tx = body.getValue("transaction_details").jsonObject
            // Unquoted numbers, and six decimals survive: a double would not hold 5.123456 exactly.
            assertTrue("\"crypto_amount\":5.123456" in payload, payload)
            assertEquals(false, tx.getValue("crypto_amount").jsonPrimitive.isString)
            assertEquals("539.26", tx.getValue("fiat_amount").jsonPrimitive.content)
            assertEquals(SMART_ACCOUNT.lowercaseHex, tx.getValue("recipient_address").jsonPrimitive.content)
            assertEquals("UPI", tx.getValue("payment_method").jsonPrimitive.content)
            assertEquals("1-3 minutes", tx.getValue("estimated_processing_time").jsonPrimitive.content)
            assertEquals(
                "India",
                body
                    .getValue("user_details")
                    .jsonObject
                    .getValue("country")
                    .jsonPrimitive
                    .content,
            )
            // The body timestamp is milliseconds.
            assertEquals("1756450000000", tx.getValue("order_timestamp").jsonPrimitive.content)
        }

    @Test
    fun `the record names the app that filed it, on both intakes`() =
        runTest {
            // Android by default; the Apple facade names itself. The service reads the field per
            // product, so an iOS order filed as Android would be scoped to the wrong one.
            val ios =
                OnrampScreeningConfig(
                    apiUrl = "https://screening.invalid",
                    encryptionKeyHex = KEY_HEX,
                    orderSource = "zapp-ios",
                )
            OnrampScreeningKind.entries.forEach { kind ->
                assertEquals("zapp-android", client(1L).payloadJson(ORDER, "IN", 1L, kind).orderSource())
                assertEquals("zapp-ios", client(1L, ios).payloadJson(ORDER, "IN", 1L, kind).orderSource())
            }
        }

    private fun String.orderSource(): String =
        Json
            .parseToJsonElement(this)
            .jsonObject
            .getValue("transaction_details")
            .jsonObject
            .getValue("order_source")
            .jsonPrimitive
            .content

    @Test
    fun `the record says plainly that this device has no SEON session`() =
        runTest {
            val payload = client(1L).payloadJson(ORDER, country = "IN", timestampMillis = 1L)
            val device =
                Json
                    .parseToJsonElement(payload)
                    .jsonObject
                    .getValue("device_details")
                    .jsonObject
            assertTrue(device.containsKey("seonSession"))
            assertEquals(true, device.getValue("seonSession") is kotlinx.serialization.json.JsonNull)
        }

    @Test
    fun `the encrypted payload is iv then ciphertext then tag, with a fresh iv each time`() {
        val subject = client(1L)
        val plaintext = "{\"a\":1}"
        val first = Base64.decode(subject.encrypt(plaintext, aad = "buy_order|0xabc|1"))
        val second = Base64.decode(subject.encrypt(plaintext, aad = "buy_order|0xabc|1"))

        // 12-byte GCM nonce + ciphertext (same length as the plaintext) + 16-byte tag.
        assertEquals(IV_BYTES + plaintext.length + TAG_BYTES, first.size)
        // A reused nonce under a fixed key is catastrophic for GCM; this is the observable proof
        // that each call draws a new one.
        assertNotEquals(
            first.copyOfRange(0, IV_BYTES).toList(),
            second.copyOfRange(0, IV_BYTES).toList(),
        )
    }

    @Test
    fun `a 200 that does not mention approved is unavailable, not a rejection`() =
        runTest {
            // ☠ The whole corridor rides on this. Rejected is the one outcome that stops a
            // placement, so defaulting an absent field to "not approved" would turn any envelope
            // change on the service into every Android buy failing, worded as a refusal.
            var reported: String? = null
            val outcome =
                clientAnswering(
                    """{"status":"ok","data":{"activity_log_id":"a-1"}}""",
                    onScreeningUnavailable = { reported = it },
                ).screenBuyOrder(signer, ORDER, country = "IN")

            assertEquals(OnrampScreeningOutcome.Unavailable, outcome)
            // Unavailable is invisible from the screen, so the drift has to reach the log.
            assertTrue(reported.orEmpty().contains("without an approval"), "got: $reported")
        }

    @Test
    fun `on the b2b intake an id alone clears, as its reference client reads it`() =
        runTest {
            // The widget types `approved` as optional there and links on the id whenever the
            // field is not `false`; reading it the consumer way would leave the order unlinked.
            var reported: String? = null
            val outcome =
                clientAnswering("""{"activity_log_id":9}""", onScreeningUnavailable = { reported = it })
                    .screenBuyOrder(signer, ORDER, country = "IN", kind = OnrampScreeningKind.B2B)

            assertEquals("9", assertIs<OnrampScreeningOutcome.Approved>(outcome).activityLogId.jsonPrimitive.content)
            assertNull(reported)
            // An explicit refusal still wins, and a null id is no id.
            assertIs<OnrampScreeningOutcome.Rejected>(
                clientAnswering("""{"approved":false,"activity_log_id":9,"message":"cluster"}""")
                    .screenBuyOrder(signer, ORDER, country = "IN", kind = OnrampScreeningKind.B2B),
            )
            assertEquals(
                OnrampScreeningOutcome.Unavailable,
                clientAnswering("""{"activity_log_id":null}""")
                    .screenBuyOrder(signer, ORDER, country = "IN", kind = OnrampScreeningKind.B2B),
            )
        }

    @Test
    fun `a body that cannot be read and a service that cannot be reached are both reported, not thrown`() =
        runTest {
            val reports = mutableListOf<String>()

            assertEquals(
                OnrampScreeningOutcome.Unavailable,
                clientAnswering("<html>bad gateway</html>", onScreeningUnavailable = reports::add)
                    .screenBuyOrder(signer, ORDER, country = "IN"),
            )
            assertEquals(
                OnrampScreeningOutcome.Unavailable,
                clientFailing(onScreeningUnavailable = reports::add)
                    .screenBuyOrder(signer, ORDER, country = "IN", kind = OnrampScreeningKind.B2B),
            )

            assertEquals(2, reports.size, "$reports")
            assertTrue(reports[0].startsWith("/activity-logs failed:"), reports[0])
            assertTrue(reports[1].startsWith("/activity-logs/b2b-buy-order failed:"), reports[1])
        }

    @Test
    fun `only an explicit approved false stops the order`() =
        runTest {
            val outcome =
                clientAnswering("""{"approved":false,"message":"sanctioned jurisdiction"}""")
                    .screenBuyOrder(signer, ORDER, country = "IN")

            // The service's own sentence has to survive: it is what the user is shown, and it is
            // routinely more specific than anything we can say from a failure code.
            assertEquals("sanctioned jurisdiction", assertIs<OnrampScreeningOutcome.Rejected>(outcome).message)
        }

    @Test
    fun `an approval with no activity log id is unavailable, since there is nothing to link`() =
        runTest {
            var reported: String? = null
            val outcome =
                clientAnswering("""{"approved":true}""", onScreeningUnavailable = { reported = it })
                    .screenBuyOrder(signer, ORDER, country = "IN")

            assertEquals(OnrampScreeningOutcome.Unavailable, outcome)
            assertTrue(reported.orEmpty().contains("without an activity_log_id"), "got: $reported")
        }

    @Test
    fun `a refusal with a null message shows nothing rather than the word null`() =
        runTest {
            val outcome =
                clientAnswering("""{"approved":false,"message":null,"reason":"user_restricted"}""")
                    .screenBuyOrder(signer, ORDER, country = "IN")

            assertEquals("", assertIs<OnrampScreeningOutcome.Rejected>(outcome).message)
        }

    @Test
    fun `an approval carries the id the order will be linked to`() =
        runTest {
            val outcome =
                clientAnswering("""{"approved":true,"activity_log_id":"a-1"}""")
                    .screenBuyOrder(signer, ORDER, country = "IN")

            assertEquals("a-1", assertIs<OnrampScreeningOutcome.Approved>(outcome).activityLogId.jsonPrimitive.content)
        }

    @Test
    fun `a consumer record names its type in the envelope and goes to the consumer intake`() =
        runTest {
            var sent: HttpRequestData? = null
            clientAnswering("""{"approved":true,"activity_log_id":"a-1"}""", record = { sent = it })
                .screenBuyOrder(signer, ORDER, country = "IN")

            val request = checkNotNull(sent)
            assertEquals("/api/v1/activity-logs", request.url.encodedPath)
            assertEquals(
                "buy_order",
                request
                    .envelope()
                    .getValue("type")
                    .jsonPrimitive.content
            )
        }

    @Test
    fun `a b2b record is typed by its path, bound under its own aad, and keyed the way that intake reads`() =
        runTest {
            // ☠ Three silent ways for an integrator order to go unaccepted: the wrong path files a
            // consumer record for an order the consumer engine will score as a new account; the
            // wrong AAD decrypts to nothing; camelCase device keys are simply unread.
            var sent: HttpRequestData? = null
            val outcome =
                clientAnswering("""{"approved":true,"activity_log_id":7}""", record = { sent = it })
                    .screenBuyOrder(signer, ORDER, country = "IN", kind = OnrampScreeningKind.B2B)

            assertEquals("7", assertIs<OnrampScreeningOutcome.Approved>(outcome).activityLogId.jsonPrimitive.content)
            val request = checkNotNull(sent)
            assertEquals("/api/v1/activity-logs/b2b-buy-order", request.url.encodedPath)
            val envelope = request.envelope()
            assertFalse(envelope.containsKey("type"), "the B2B envelope carries no type: $envelope")
            assertEquals(SMART_ACCOUNT.lowercaseHex, envelope.getValue("user_address").jsonPrimitive.content)

            val payload = decrypt(envelope, aadPrefix = "b2b_buy_order")
            assertEquals(OnrampScreeningConfig.DEFAULT_B2B_DOMAIN, payload.getValue("domain").jsonPrimitive.content)
            val device = payload.getValue("device_details").jsonObject
            assertEquals(SIGNALS.userAgent, device.getValue("user_agent").jsonPrimitive.content)
            assertEquals("-330", device.getValue("timezone_offset").jsonPrimitive.content)
            assertEquals(JsonNull, device.getValue("seon_session"))
            assertFalse(device.containsKey("userAgent"), "camelCase keys must not survive: ${device.keys}")
            // The transaction block is the consumer one, unchanged.
            assertEquals(
                "539.26",
                payload
                    .getValue("transaction_details")
                    .jsonObject
                    .getValue("fiat_amount")
                    .jsonPrimitive
                    .content,
            )
            // Same signed action on both intakes, so the headers are the consumer headers.
            assertEquals(
                client(1_756_450_000_123L).signedHeaders(signer, "activity-log")["X-Signature"],
                request.headers["X-Signature"],
            )
        }

    @Test
    fun `a refused intake is reported, since a record never filed is an order never accepted`() =
        runTest {
            var reported: String? = null
            val outcome =
                clientAnswering(
                    body = """{"error":"key not enabled for b2b"}""",
                    status = HttpStatusCode.Forbidden,
                    onScreeningUnavailable = { reported = it },
                ).screenBuyOrder(signer, ORDER, country = "IN", kind = OnrampScreeningKind.B2B)

            // Still fail-open: the order places.
            assertEquals(OnrampScreeningOutcome.Unavailable, outcome)
            val report = reported.orEmpty()
            assertTrue(report.contains("403"), "the status code has to survive into the log, got: $report")
            assertTrue(report.contains("b2b-buy-order"), "and which intake refused it: $report")
        }

    @Test
    fun `a rejected link reports the status it was refused with`() =
        runTest {
            // ☠ The reason this is a test at all: the callback took a string, and a string is where
            // an escaping slip hides. Asserting on the *code* is what makes it real — a message
            // that merely mentions a failure tells you nothing a 401 and a 503 do not share.
            var reported: String? = null
            clientAnswering(
                body = """{"error":"nope"}""",
                status = HttpStatusCode.Unauthorized,
                onLinkFailed = { reported = it },
            ).linkOrder(signer, JsonPrimitive("a-1"), orderId = bigIntegerValueOf(7))

            assertTrue(
                reported.orEmpty().contains("401"),
                "the status code has to survive into the log, got: $reported",
            )
        }

    @Test
    fun `a linked order says nothing at all`() =
        runTest {
            var reported: String? = null
            clientAnswering(
                body = """{"ok":true}""",
                onLinkFailed = { reported = it },
            ).linkOrder(signer, JsonPrimitive("a-1"), orderId = bigIntegerValueOf(7))

            assertNull(reported)
        }

    private fun HttpRequestData.envelope(): JsonObject =
        Json.parseToJsonElement((body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject

    /** Opens the payload exactly as the service does: same key, and the AAD rebuilt from the envelope. */
    private fun decrypt(envelope: JsonObject, aadPrefix: String): JsonObject {
        val subject = envelope.getValue("user_address").jsonPrimitive.content
        val timestamp = envelope.getValue("timestamp").jsonPrimitive.content
        val ciphertext = Base64.decode(envelope.getValue("encrypted_payload").jsonPrimitive.content)
        val key =
            CryptographyProvider.Default
                .get(AES.GCM)
                .keyDecoder()
                .decodeFromByteArrayBlocking(AES.Key.Format.RAW, KEY_HEX.hexToBytes())
        val plaintext = key.cipher().decryptBlocking(ciphertext, "$aadPrefix|$subject|$timestamp".encodeToByteArray())
        return Json.parseToJsonElement(plaintext.decodeToString()).jsonObject
    }

    private companion object {
        const val SIGNING_KEY_HEX = "0x59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d"
        const val KEY_HEX = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
        const val IV_BYTES = 12
        const val TAG_BYTES = 16

        /** 0x + r(64) + s(64) + v(2). */
        const val SIGNATURE_HEX_LEN = 132

        val SMART_ACCOUNT: Address = Address.parse("0x448f857Ea117138E85D062C6Ce89E90A337874d6")

        val ORDER =
            OnrampScreeningOrder(
                cryptoAmount = Usdc6.ofMicros(5_123_456L),
                fiatAmount = Usdc6.ofMicros(539_260_000L),
                currency = CurrencyCode.Inr,
                recipientAddress = SMART_ACCOUNT,
                fee = Usdc6.ofMicros(50_000L),
                amountAfterFee = Usdc6.ofMicros(5_073_456L),
                paymentMethod = "UPI",
                estimatedProcessingTime = "1-3 minutes",
            )

        val SIGNALS =
            OnrampDeviceSignals(
                userAgent = "Zapp/4.6.4 (Android 14)",
                platform = "Android",
                language = "en-IN",
                languages = listOf("en-IN"),
                screenWidth = 1080,
                screenHeight = 2400,
                devicePixelRatio = 3.0,
                timezone = "Asia/Kolkata",
                timezoneOffset = -330,
                cookiesEnabled = true,
                doNotTrack = null,
                online = true,
                touchSupport = true,
                maxTouchPoints = 5,
                vendor = "Google",
                appVersion = "4.6.4",
                colorDepth = 24,
                pixelDepth = 24,
            )
    }
}
