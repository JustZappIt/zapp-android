// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.onramp

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
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

    private fun client(nowMillis: Long) =
        OnrampScreeningClient(
            httpClient = HttpClient(),
            config = OnrampScreeningConfig(apiUrl = "https://screening.invalid/api/v1", encryptionKeyHex = KEY_HEX),
            deviceSignals = { SIGNALS },
            nowMillis = { nowMillis },
        )

    /** A client whose screening endpoint answers with exactly [body]. */
    private fun clientAnswering(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
        onLinkFailed: (String) -> Unit = {},
    ) = OnrampScreeningClient(
        httpClient =
            HttpClient(
                MockEngine { respond(body, status, headersOf(HttpHeaders.ContentType, "application/json")) },
            ),
        config = OnrampScreeningConfig(apiUrl = "https://screening.invalid/api/v1", encryptionKeyHex = KEY_HEX),
        deviceSignals = { SIGNALS },
        nowMillis = { 1_756_450_000_123L },
        onLinkFailed = onLinkFailed,
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
            val payload = client(1_756_450_000_000L).payloadJson(ORDER, country = "IN")
            val body = Json.parseToJsonElement(payload).jsonObject
            val tx = body.getValue("transaction_details").jsonObject
            // Unquoted numbers, and six decimals survive: a double would not hold 5.123456 exactly.
            assertTrue("\"crypto_amount\":5.123456" in payload, payload)
            assertEquals(false, tx.getValue("crypto_amount").jsonPrimitive.isString)
            assertEquals("539.26", tx.getValue("fiat_amount").jsonPrimitive.content)
            assertEquals(SMART_ACCOUNT.lowercaseHex, tx.getValue("recipient_address").jsonPrimitive.content)
            // The body timestamp is milliseconds.
            assertEquals("1756450000000", tx.getValue("order_timestamp").jsonPrimitive.content)
        }

    @Test
    fun `the record says plainly that this device has no SEON session`() =
        runTest {
            val payload = client(1L).payloadJson(ORDER, country = "IN")
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
            val outcome =
                clientAnswering("""{"status":"ok","data":{"activity_log_id":"a-1"}}""")
                    .screenBuyOrder(signer, ORDER, country = "IN")

            assertEquals(OnrampScreeningOutcome.Unavailable, outcome)
        }

    @Test
    fun `only an explicit approved false stops the order`() =
        runTest {
            val outcome =
                clientAnswering("""{"approved":false,"message":"sanctioned jurisdiction"}""")
                    .screenBuyOrder(signer, ORDER, country = "IN")

            assertEquals(OnrampScreeningOutcome.Rejected, outcome)
        }

    @Test
    fun `an approval with no activity log id is unavailable, since there is nothing to link`() =
        runTest {
            val outcome =
                clientAnswering("""{"approved":true}""")
                    .screenBuyOrder(signer, ORDER, country = "IN")

            assertEquals(OnrampScreeningOutcome.Unavailable, outcome)
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
                estimatedProcessingTimeSeconds = 120L,
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
