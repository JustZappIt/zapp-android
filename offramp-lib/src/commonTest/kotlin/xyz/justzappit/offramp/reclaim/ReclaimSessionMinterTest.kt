// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.reclaim

import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import xyz.justzappit.evm.types.Address
import xyz.justzappit.offramp.reputation.SocialPlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every expected value here came from ethers 6 + Reclaim's own `canonicalize`, run over the same
 * inputs — the two libraries the Verifier app and Reclaim's backend actually agree with. These
 * three values are where a port goes wrong quietly: the init signature (whose EIP-191 variant is
 * the one thing that decides whether `init/session` answers at all), the TEE nonce (which hashes
 * a colon-joined string, not a struct), and the context (whose bytes the attestor signs, so key
 * order is protocol, not style).
 */
class ReclaimSessionMinterTest {
    private val minter =
        ReclaimSessionMinter(
            httpClient = HttpClient(),
            credentials = ReclaimAppCredentials(appId = APP_ID, appSecret = APP_SECRET),
            nowMillis = { TIMESTAMP.toLong() },
            redirectUrl = RETURN_URL,
        )

    @Test
    fun `the init signature matches ethers signMessage over the 32-byte digest`() {
        // ☠ The fixed-length ":\n32" variant. The variable-length form OnrampRequestSigner uses
        // recovers a different address, and Reclaim answers with a generic init failure.
        assertEquals(ETHERS_SIGNATURE, minter.signInit(PROVIDER_ID, TIMESTAMP))
    }

    @Test
    fun `the TEE attestation nonce matches the SDK's keccak of the joined payload`() {
        assertEquals(ETHERS_NONCE, minter.attestationNonce(SESSION_ID, TIMESTAMP))
    }

    @Test
    fun `the context serialises byte-for-byte like Reclaim's canonicalize`() {
        val context = minter.context(ETHERS_NONCE, SESSION_ID, TIMESTAMP, SocialPlatform.X, CONTEXT_ADDRESS)
        assertEquals(CANONICAL_CONTEXT, CanonicalJson.stringify(context))
    }

    @Test
    fun `the context carries the smart account, which is what msg sender will be`() {
        val context = minter.context(ETHERS_NONCE, SESSION_ID, TIMESTAMP, SocialPlatform.X, CONTEXT_ADDRESS)
        assertEquals(
            CONTEXT_ADDRESS.checksumHex,
            context["contextAddress"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `the share link is the deferred deep link path and decodes back to the template`() {
        val context = minter.context(ETHERS_NONCE, SESSION_ID, TIMESTAMP, SocialPlatform.X, CONTEXT_ADDRESS)
        val template =
            minter.templateData(
                sessionId = SESSION_ID,
                providerId = PROVIDER_ID,
                timestamp = TIMESTAMP,
                signature = ETHERS_SIGNATURE,
                context = context,
                resolvedProviderVersion = "21.0.0",
            )
        val link = minter.shareLink(template)
        // /link/, not /verifier/: the path that survives a store install and still resumes here.
        assertTrue(link.startsWith("https://share.reclaimprotocol.org/link/?template="))
        assertTrue("(" !in link && ")" !in link, "parentheses must be percent-encoded")

        val decoded = decodeUrlComponent(link.substringAfter("?template="))
        val roundTripped = Json.parseToJsonElement(decoded) as JsonObject
        assertEquals(SESSION_ID, roundTripped["sessionId"]?.jsonPrimitive?.content)
        assertEquals("js-5.8.2", roundTripped["sdkVersion"]?.jsonPrimitive?.content)
        assertEquals("21.0.0", roundTripped["resolvedProviderVersion"]?.jsonPrimitive?.content)
        // The context travels as a *string* holding JSON; a nested object hashes differently.
        assertEquals(CANONICAL_CONTEXT, roundTripped["context"]?.jsonPrimitive?.content)
    }

    @Test
    fun `the template carries somewhere for the Verifier to send the user back to`() {
        val template =
            minter.templateData(
                sessionId = "s-1",
                providerId = "p-1",
                timestamp = "1700000000000",
                signature = "0xsig",
                context = buildJsonObject { put("contextAddress", "0x1") },
                resolvedProviderVersion = "21.0.0",
            )

        // Empty here is what left users on Reclaim's "you can now return to Zapp" screen with no
        // way back but the launcher. Cancelling has to come back too.
        assertEquals(RETURN_URL, template["redirectUrl"]?.jsonPrimitive?.content)
        assertEquals(RETURN_URL, template["cancelRedirectUrl"]?.jsonPrimitive?.content)
    }

    @Test
    fun `the store fallback is a scheme Compose's UriHandler can actually open`() {
        val url = minter.installIntentUrl()

        // AndroidUriHandler does ACTION_VIEW on Uri.parse. `market:` resolves to Play; `intent:`
        // resolves to nothing and throws, which is what this used to emit.
        assertEquals("market://details?id=org.reclaimprotocol.app", url)
    }

    private fun decodeUrlComponent(value: String): String {
        val bytes = mutableListOf<Byte>()
        var i = 0
        while (i < value.length) {
            when (value[i]) {
                '%' -> {
                    bytes += value.substring(i + 1, i + 3).toInt(16).toByte()
                    i += 3
                }

                '+' -> {
                    bytes += ' '.code.toByte()
                    i++
                }

                else -> {
                    bytes += value[i].code.toByte()
                    i++
                }
            }
        }
        return bytes.toByteArray().decodeToString()
    }

    private companion object {
        // A throwaway key — a Reclaim appSecret is exactly this: an Ethereum private key.
        const val APP_SECRET = "0x59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d"

        /** Stands in for the app's own scheme; the minter never inspects it. */
        const val RETURN_URL = "zcash://reclaim-return"

        const val APP_ID = "0x70997970C51812dc3A010C7d01b50e0d17dc79C8"
        const val PROVIDER_ID = "aad95818-f726-4a34-be97-8d1f47631b03"
        const val TIMESTAMP = "1788010828404"
        const val SESSION_ID = "f721543b42"

        val CONTEXT_ADDRESS: Address = Address.parse("0x448f857Ea117138E85D062C6Ce89E90A337874d6")

        const val ETHERS_SIGNATURE =
            "0xc4d2765f5548df99bee6e2d4457b211582274e37dd83fb3f942eece0fe0106dd" +
                "03dcb3e02ec782bb990b0d0d74e962c830182db69769109af0f03ea328c00c431c"
        const val ETHERS_NONCE = "ba32f5ed831dd7c079dfbbf0431a4822e7cb8952835fc3e25b0282f36e7a8a6d"

        const val CANONICAL_CONTEXT =
            "{\"attestationNonce\":\"ba32f5ed831dd7c079dfbbf0431a4822e7cb8952835fc3e25b0282f36e7a8a6d\"," +
                "\"attestationNonceData\":{\"applicationId\":\"0x70997970C51812dc3A010C7d01b50e0d17dc79C8\"," +
                "\"attestationVersion\":\"v3\",\"sessionId\":\"f721543b42\"," +
                "\"timestamp\":\"1788010828404\"}," +
                "\"contextAddress\":\"0x448f857Ea117138E85D062C6Ce89E90A337874d6\"," +
                "\"contextMessage\":\"Social verification for X\"," +
                "\"reclaimSessionId\":\"f721543b42\"}"
    }
}
