// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.app

import android.content.pm.PackageManager
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapAttestation
import co.electriccoin.zcash.ui.common.atomicswap.PrivacyPassTokens
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Url
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import xyz.justzappit.evm.util.toHex
import xyz.justzappit.offramp.atomicswap.SwapTokenIssuer
import xyz.justzappit.offramp.atomicswap.SwapTokenState
import xyz.justzappit.offramp.atomicswap.SwapTokenStore
import xyz.justzappit.offramp.atomicswap.SwapTokens
import java.io.File
import java.security.MessageDigest
import kotlin.io.encoding.Base64

/**
 * This install's key against a zecSwap token issuer in `android-key` mode, at the URL the `issuer` argument gives
 * (skipped without one), fetched from as the app does but directly rather than over Tor: a fetch gets the day's
 * allowance and the next none. The first request sent is written to the app's files, `captured.json`, as zecSwap's
 * `fixtures/android-key` takes it. Only a phone with a locked bootloader passes: the issuer refuses any other.
 */
@SdkSuppress(minSdkVersion = 33)
class AtomicSwapAttestationPhoneTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val sent = mutableListOf<Sent>()

    @Test
    fun anInstallsKeyGetsTheDaysTokensAndNoMore() =
        runBlocking {
            val url = InstrumentationRegistry.getArguments().getString("issuer")
            assumeTrue("needs -e issuer <url>", url != null)
            val answer = HttpClient(OkHttp).use { it.get("$url/v1/token-key").bodyAsText() }
            val published = Json.parseToJsonElement(answer).jsonObject
            val name = published.text("issuer")
            val tokenKey = published.text("tokenKey")
            // Nothing is handed back here: the token key stands in for a maker's.
            val issuer = SwapTokenIssuer(Url(checkNotNull(url)), name, tokenKey, returnKey = tokenKey)
            val attestation = AtomicSwapAttestation(name)

            // Written whatever the issuer answers: a refused request shows what the phone attested just the same.
            val first =
                try {
                    fetch(issuer, attestation)
                } finally {
                    sent.firstOrNull()?.let { capture(name, it) }
                }
            assertEquals(published.text("tokensPerDay").toInt(), first.held.size)
            val next = fetch(issuer, attestation)
            assertTrue("the next fetch is answered 429", next.held.isEmpty() && next.exhaustedDay != null)
        }

    private suspend fun fetch(
        issuer: SwapTokenIssuer,
        attestation: AtomicSwapAttestation
    ): SwapTokenState {
        val store = Memory(SwapTokenState(challenge = makerChallenge(issuer.name)))
        SwapTokens(issuer, { client() }, PrivacyPassTokens, attestation, store).prefetch()
        return store.state
    }

    private fun client() =
        HttpClient(OkHttp) {
            engine {
                addInterceptor { chain ->
                    val request = chain.request()
                    if (request.url.encodedPath.endsWith("/v1/tokens")) {
                        val body = Buffer().also { request.body?.writeTo(it) }.readUtf8()
                        sent += Sent(System.currentTimeMillis() / MILLIS_PER_SECOND, body)
                    }
                    chain.proceed(request)
                }
            }
        }

    private fun capture(
        issuer: String,
        request: Sent
    ) {
        val body = Json.parseToJsonElement(request.body).jsonObject
        val captured =
            buildJsonObject {
                put("issuer", issuer)
                put("package", context.packageName)
                put("signingDigest", signingDigest())
                put("at", request.at)
                put("blinded", body.getValue("blinded"))
                put("attestation", body.getValue("attestation"))
            }
        File(context.filesDir, "captured.json").writeText(captured.toString())
    }

    private fun signingDigest(): String {
        val flags = PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
        val signing = checkNotNull(context.packageManager.getPackageInfo(context.packageName, flags).signingInfo)
        return MessageDigest.getInstance("SHA-256").digest(signing.apkContentsSigners.single().toByteArray()).toHex()
    }

    private class Sent(
        val at: Long,
        val body: String,
    )

    private class Memory(
        var state: SwapTokenState,
    ) : SwapTokenStore {
        override suspend fun load() = state

        override suspend fun save(state: SwapTokenState) {
            this.state = state
        }
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1000L
        const val SECONDS_PER_DAY = 86_400L

        fun JsonObject.text(name: String) = getValue(name).jsonPrimitive.content

        /** RFC 9577's challenge as zecSwap's makers date theirs: today's UTC day in the last 8 bytes of its context. */
        fun makerChallenge(issuer: String): String {
            val name = issuer.encodeToByteArray()
            val today = System.currentTimeMillis() / MILLIS_PER_SECOND / SECONDS_PER_DAY
            val day = ByteArray(Long.SIZE_BYTES) { (today ushr Byte.SIZE_BITS * (Long.SIZE_BYTES - 1 - it)).toByte() }
            val context = byteArrayOf(32) + ByteArray(24) + day
            val origin = byteArrayOf(0, 5) + "maker".encodeToByteArray()
            val challenge = byteArrayOf(0, 2, 0, name.size.toByte()) + name + context + origin
            return Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(challenge)
        }
    }
}
