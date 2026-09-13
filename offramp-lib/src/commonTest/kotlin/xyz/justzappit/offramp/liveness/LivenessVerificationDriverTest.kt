// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.liveness

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.justzappit.evm.hd.EvmKey
import xyz.justzappit.evm.hd.EvmKeyDerivation
import xyz.justzappit.evm.math.bigIntegerValueOf
import xyz.justzappit.evm.rpc.BaseRpcClient
import xyz.justzappit.evm.rpc.BundlerClient
import xyz.justzappit.evm.signer.ThirdwebSmartAccount
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.util.toHex
import xyz.justzappit.offramp.account.Erc4337SubmitterProvider
import xyz.justzappit.offramp.account.OfframpAccountProvider
import xyz.justzappit.offramp.account.SmartOfframpAccountProvider
import xyz.justzappit.offramp.config.P2pNetworks
import xyz.justzappit.offramp.p2p.CurrencyCode
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the driver decides between the redirect and the bundler. Everything here stops before a
 * UserOp is sent — the bundler mock throws — because each of these is a refusal the user should
 * read before any of that, and a wrong one either burns a selfie or submits for the wrong wallet.
 */
class LivenessVerificationDriverTest {
    private val owner: EvmKey = EvmKeyDerivation.derive(MNEMONIC, accountIndex = 0)
    private val network = P2pNetworks.SEPOLIA
    private val smartAccount = Address.parse(SMART_ACCOUNT)

    /** Selectors from the real calldata builders, so a signature change fails here first. */
    private val getAddressSelector = ThirdwebSmartAccount.getAddressCalldata(owner.address).selector()
    private val submitSelector = LivenessCalls.submitAttestationCalldata(sampleAttestation()).selector()

    /** The integrator's answer to simulating the submit, as revert data; null lets it pass. */
    private var nextRevert: String? = null
    private var simulated = false

    /** What `/v1/widget/result` hands back; the wallet is the one thing a test changes. */
    private var attestedWallet = SMART_ACCOUNT
    private var resultStatus = HttpStatusCode.OK
    private var sessionRequest: JsonObject? = null
    private var redeemed = false

    private val rpcEngine =
        MockEngine { request ->
            val payload = Json.parseToJsonElement(request.bodyText()).jsonObject
            val method = payload["method"]!!.jsonPrimitive.content
            val params = payload["params"].toString()
            val revert = nextRevert
            when {
                method != "eth_call" -> {
                    error("Unexpected RPC method before the bundler: $method")
                }

                getAddressSelector in params -> {
                    respond(rpcResult(ENCODED_SMART_ACCOUNT), HttpStatusCode.OK, jsonHeaders)
                }

                submitSelector in params && revert != null -> {
                    simulated = true
                    respond(
                        """{"jsonrpc":"2.0","id":1,"error":""" +
                            """{"code":3,"message":"execution reverted","data":"$revert"}}""",
                        HttpStatusCode.OK,
                        jsonHeaders,
                    )
                }

                submitSelector in params -> {
                    simulated = true
                    respond(rpcResult("0x"), HttpStatusCode.OK, jsonHeaders)
                }

                else -> {
                    error("Unexpected eth_call before the bundler: $params")
                }
            }
        }

    private val widgetEngine =
        MockEngine { request ->
            assertEquals(API_KEY, request.headers[API_KEY_HEADER], "every widget call carries the tenant key")
            when (request.url.encodedPath) {
                "/v1/widget/sessions" -> {
                    sessionRequest = Json.parseToJsonElement(request.bodyText()).jsonObject
                    respond(
                        """{"widget_url":"$WIDGET_URL","handoff_token":"handoff","expires_in":900}""",
                        HttpStatusCode.OK,
                        jsonHeaders,
                    )
                }

                "/v1/widget/result" -> {
                    redeemed = true
                    respond(resultBody(), resultStatus, jsonHeaders)
                }

                else -> {
                    error("Unexpected widget call: ${request.url}")
                }
            }
        }

    private val rpcHttp = HttpClient(rpcEngine) { install(ContentNegotiation) { json() } }
    private val widgetHttp = HttpClient(widgetEngine)
    private val bundlerHttp = HttpClient(MockEngine { error("nothing here may reach the bundler") })
    private val rpc = BaseRpcClient(rpcHttp, "http://mock/rpc")

    @AfterTest
    fun shutdown() {
        rpcHttp.close()
        widgetHttp.close()
        bundlerHttp.close()
    }

    @Test
    fun `an unconfigured build fails before touching the network`() =
        runTest {
            val unconfigured = driver(config = LivenessConfig(apiUrl = "", apiKey = "", tenant = ""))

            val statuses = unconfigured.verify(CurrencyCode.Inr, NONCE, LivenessReturnSignal()).toList()

            assertEquals(listOf(LivenessStatus.Failed(LivenessFailure.NotConfigured)), statuses)
            assertNull(sessionRequest)
        }

    @Test
    fun `the session is opened for the smart account and the corridor rides in state`() =
        runTest {
            val signal = LivenessReturnSignal().also { it.deliver(cancelled()) }

            val statuses = driver().verify(CurrencyCode.Inr, NONCE, signal).toList()

            // §5.2 again: the attestation names msg.sender, which is the smart account, never the owner.
            val session = sessionRequest!!
            assertEquals(smartAccount.checksumHex, session["wallet_pubkey"]?.jsonPrimitive?.content)
            assertEquals(TENANT, session["tenant"]?.jsonPrimitive?.content)
            assertEquals(REDIRECT_URI, session["redirect_uri"]?.jsonPrimitive?.content)
            assertEquals("$NONCE.INR", session["state"]?.jsonPrimitive?.content)
            assertEquals(
                listOf(LivenessStatus.Preparing, LivenessStatus.Ready(WIDGET_URL, 900), LivenessStatus.Verifying),
                statuses.dropLast(1),
            )
        }

    @Test
    fun `an error return is taken at its word`() =
        runTest {
            // ☠ The widget sends no state beside an error, so binding state on every return
            // turned a cancel into "rejected". Each reason also has to survive as itself: the
            // duplicate one is permanent, the other two are worth a retry.
            val outcomes =
                listOf("cancelled", "duplicate_person", "expired", "liveness_failed").map { error ->
                    val signal = LivenessReturnSignal().also { it.deliver(LivenessReturn(null, error, null)) }
                    assertIs<LivenessStatus.Failed>(driver().verify(CurrencyCode.Inr, NONCE, signal).toList().last())
                        .reason
                }

            assertEquals(
                listOf(
                    LivenessFailure.Cancelled,
                    LivenessFailure.AlreadyClaimed,
                    LivenessFailure.Expired,
                    LivenessFailure.NotLive,
                ),
                outcomes,
            )
            assertTrue(!redeemed, "an error return has no code to redeem")
        }

    @Test
    fun `a code with another session's state is refused unredeemed`() =
        runTest {
            val signal = LivenessReturnSignal().also { it.deliver(LivenessReturn(CODE, null, "other.INR")) }

            val statuses = driver().verify(CurrencyCode.Inr, NONCE, signal).toList()

            assertEquals(LivenessFailure.Rejected, assertIs<LivenessStatus.Failed>(statuses.last()).reason)
            assertTrue(!redeemed, "a foreign code is never spent on this wallet's behalf")
        }

    @Test
    fun `an attestation naming another wallet is never submitted`() =
        runTest {
            attestedWallet = OTHER_WALLET

            val failed = assertIs<LivenessStatus.Failed>(driver().resume(success()).toList().last())

            assertEquals(LivenessFailure.Rejected, failed.reason)
            assertTrue(redeemed)
            assertTrue(!simulated, "a mismatched attestation must not even be simulated")
        }

    @Test
    fun `a spent nullifier is a sentence before any bundler`() =
        runTest {
            nextRevert = NULLIFIER_ALREADY_SPENT

            val statuses = driver().resume(success()).toList()

            assertEquals(listOf(LivenessStatus.Verifying, LivenessStatus.Submitting), statuses.dropLast(1))
            assertEquals(LivenessFailure.AlreadyClaimed, assertIs<LivenessStatus.Failed>(statuses.last()).reason)
            assertTrue(simulated)
        }

    @Test
    fun `a code the service no longer has is an expired check`() =
        runTest {
            resultStatus = HttpStatusCode.BadRequest

            val failed = assertIs<LivenessStatus.Failed>(driver().resume(success()).toList().last())

            assertEquals(LivenessFailure.Expired, failed.reason)
        }

    @Test
    fun `an approval the tenant cannot put on chain is a configuration failure`() =
        runTest {
            attestedWallet = ""

            val failed = assertIs<LivenessStatus.Failed>(driver().resume(success()).toList().last())

            assertEquals(LivenessFailure.NotConfigured, failed.reason)
        }

    private fun driver(config: LivenessConfig = LivenessConfig(API_URL, API_KEY, TENANT)): LivenessVerificationDriver {
        val smartAccounts =
            SmartOfframpAccountProvider(
                accountProvider = FixedAccountProvider(owner),
                rpc = rpc,
                accountFactory = network.accountFactoryAddress,
            )
        val submitters =
            Erc4337SubmitterProvider(
                rpc = rpc,
                bundler =
                    BundlerClient(
                        httpClient = bundlerHttp,
                        bundlerUrl = "http://mock/bundler",
                        entryPoint = network.entryPointAddress,
                        chainId = network.chainId,
                    ),
                network = network,
                accountProvider = smartAccounts,
            )
        return LivenessVerificationDriver(
            widget = LivenessWidgetClient(widgetHttp, config, REDIRECT_URI),
            reader = LivenessReader(rpc, network),
            submitters = submitters,
            rpc = rpc,
            network = network,
            config = config,
        )
    }

    private fun sampleAttestation() =
        LivenessAttestation(
            wallet = smartAccount,
            nullifier = ByteArray(LivenessAttestation.NULLIFIER_BYTES) { 0x11 },
            limit = bigIntegerValueOf(75_000_000L),
            expiry = 1_760_000_000L,
            signature = ByteArray(LivenessAttestation.SIGNATURE_BYTES) { 0xab.toByte() },
            attestor = Address.parse(OTHER_WALLET),
        )

    private fun cancelled() = LivenessReturn(code = null, error = "cancelled", state = null)

    private fun success() = LivenessReturn(code = CODE, error = null, state = "$NONCE.INR")

    /** The `/v1/widget/result` body; an empty wallet stands for a tenant bound to no contract. */
    private fun resultBody(): String {
        val attestation =
            if (attestedWallet.isEmpty()) {
                "null"
            } else {
                """
                {
                  "signature": "0x${"ab".repeat(64)}1b",
                  "digest": "0x${"cd".repeat(32)}",
                  "domain": {"name": "ZappCheckoutIntegrator", "version": "1", "chainId": 84532},
                  "message": {
                    "wallet": "$attestedWallet",
                    "nullifier": "0x${"11".repeat(32)}",
                    "limit": 75000000,
                    "expiry": 1760000000
                  },
                  "attestor": "$OTHER_WALLET",
                  "nullifier": "0x${"11".repeat(32)}"
                }
                """.trimIndent()
            }
        return """{"decision":"approved","credential":{},"attestation":$attestation}"""
    }

    private fun rpcResult(hex: String): String = """{"jsonrpc":"2.0","id":1,"result":"$hex"}"""

    private fun ByteArray.selector(): String = copyOfRange(0, SELECTOR_BYTES).toHex()

    private fun HttpRequestData.bodyText(): String =
        (body as OutgoingContent.ByteArrayContent).bytes().decodeToString()

    private class FixedAccountProvider(
        private val key: EvmKey,
    ) : OfframpAccountProvider {
        override suspend fun nextOfframpAccount(): EvmKey = key
    }

    private companion object {
        const val MNEMONIC =
            "abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon abandon abandon about"

        const val SMART_ACCOUNT = "0x111111111111111111111111111111111111baaf"
        const val OTHER_WALLET = "0x000000000000000000000000000000000000dEaD"
        const val ENCODED_SMART_ACCOUNT =
            "0x" + "000000000000000000000000111111111111111111111111111111111111baaf"
        const val SELECTOR_BYTES = 4

        /** `ZappCheckoutIntegrator.NullifierAlreadySpent()`. */
        const val NULLIFIER_ALREADY_SPENT = "0xb115d857"

        const val API_URL = "https://liveness.example"
        const val API_KEY = "tenant-key"
        const val API_KEY_HEADER = "X-API-Key"
        const val TENANT = "zapp"
        const val REDIRECT_URI = "zcash://liveness-return"
        const val WIDGET_URL = "https://liveness.example/embed?handoff=handoff"
        const val NONCE = "3f2a9c1d8e7b6a5f4c3d2e1f0a9b8c7d"
        const val CODE = "kX9v_2Jq-7Lm3ZfQw8RtYbN4cH6sD1eA0PoIuGhVjKl"

        val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    }
}
