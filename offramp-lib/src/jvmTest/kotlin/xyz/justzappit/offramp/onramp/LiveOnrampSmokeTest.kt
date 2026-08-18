// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.onramp

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import xyz.justzappit.evm.hd.EvmKeyDerivation
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.util.hexToBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exercises the real client against the running service. Skipped unless `ONRAMP_LIVE_TEST=1`,
 * because it needs the network and the service's own availability — CI must not depend on either.
 *
 * `./gradlew :offramp-lib:jvmTest --tests "*LiveOnrampSmokeTest*" -i` with that variable set.
 *
 * It stops short of placing an order: that costs the operator a real on-chain BUY.
 */
class LiveOnrampSmokeTest {
    @Test
    fun `config, signed quote and the recipient rule all hold against the live service`() =
        runTest {
            assumeTrue(System.getenv("ONRAMP_LIVE_TEST") == "1")

            val client =
                CustodialOnrampClient(
                    httpClient = HttpClient(OkHttp),
                    baseUrl = "https://onramp.zecname.xyz",
                    signerProvider = { OnrampRequestSigner(THROWAWAY_KEY) },
                )

            val config = client.config()
            println("CONFIG enabled=${config.enabled} min=${config.minFiat} max=${config.maxFiat}")
            assertTrue(config.enabled)

            // Limits float with the rate, so they must be read rather than assumed.
            val limits = config.toLimits()
            val quote = client.quote(limits.minFiat, limits.currency).toQuote(limits.currency)
            println("QUOTE id=${quote.quoteId} fiat=${quote.fiatAmount.micros} net=${quote.netUsdc.micros}")
            assertTrue(quote.quoteId.isNotBlank())
            assertTrue(quote.netUsdc > xyz.justzappit.offramp.p2p.Usdc6.ZERO)

            // Anything but the signing address must be refused: that rule is what keeps USDC
            // settling into the user's own account.
            val refused =
                runCatching {
                    client.createOrder(quote.quoteId, Address.parse(NOT_THE_SIGNER), DEVICE)
                }.exceptionOrNull() as? OnrampException
            println("REFUSED ${refused?.code} http=${refused?.httpStatus}")
            assertEquals(OnrampFailureCode.RECIPIENT_NOT_ALLOWED, refused?.code)
        }

    private companion object {
        const val NOT_THE_SIGNER = "0x000000000000000000000000000000000000dEaD"

        val THROWAWAY_KEY =
            EvmKeyDerivation.fromPrivateKey(
                "0x4c0883a69102937d6231471b5dbb6204fe5129617082792ae468d01a3f362318".hexToBytes(),
            )

        // Mirrors what AndroidOnrampDeviceSignalsProvider builds on a real handset.
        val DEVICE =
            OnrampDeviceSignals(
                userAgent = "Dalvik/2.1.0 (Linux; U; Android 15; CPH2747 Build/AP3A)",
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
                vendor = "OnePlus",
                appVersion = "1.0.0",
                colorDepth = 24,
                pixelDepth = 24,
                connectionType = "wifi",
                deviceMemory = 8.0,
                hardwareConcurrency = 8,
            )
    }
}
