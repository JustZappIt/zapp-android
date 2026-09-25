// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.identity

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
import xyz.justzappit.offramp.reputation.IdentityCheck
import xyz.justzappit.offramp.reputation.ReputationCalls
import xyz.justzappit.offramp.reputation.ReputationReader
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the driver decides between the redirect and the bundler. Everything here stops before a
 * UserOp is sent — the bundler mock throws — because each of these is a refusal the user should
 * read before any of that.
 */
class IdentityVerificationDriverTest {
    private val owner: EvmKey = EvmKeyDerivation.derive(MNEMONIC, accountIndex = 0)
    private val network = P2pNetworks.SEPOLIA
    private val smartAccount = Address.parse(SMART_ACCOUNT)

    private val getAddressSelector = ThirdwebSmartAccount.getAddressCalldata(owner.address).selector()
    private val submitSelectors =
        IdentityCheck.entries.associateBy {
            ReputationCalls.submitIdentityAttestationCalldata(it, sampleAttestation()).selector()
        }

    /** The ReputationManager's answer to simulating the submit, as revert data; null lets it pass. */
    private var nextRevert: String? = null
    private var simulatedTo: String? = null
    private var simulated: IdentityCheck? = null

    private var attestationStatus = HttpStatusCode.OK
    private var sessionStatus = HttpStatusCode.OK
    private var sessionRequest: JsonObject? = null
    private var sessionHost: String? = null
    private var redeemed = false

    private val rpcEngine =
        MockEngine { request ->
            val payload = Json.parseToJsonElement(request.bodyText()).jsonObject
            val method = payload["method"]!!.jsonPrimitive.content
            val params = payload["params"].toString()
            val submit = submitSelectors.entries.firstOrNull { it.key in params }
            val revert = nextRevert
            when {
                method != "eth_call" -> {
                    error("Unexpected RPC method before the bundler: $method")
                }

                getAddressSelector in params -> {
                    respond(rpcResult(ENCODED_SMART_ACCOUNT), HttpStatusCode.OK, jsonHeaders)
                }

                submit != null -> {
                    simulated = submit.value
                    simulatedTo = payload["params"]!!.toString()
                    if (revert != null) {
                        respond(
                            """{"jsonrpc":"2.0","id":1,"error":""" +
                                """{"code":3,"message":"execution reverted","data":"$revert"}}""",
                            HttpStatusCode.OK,
                            jsonHeaders,
                        )
                    } else {
                        error("a passing simulation would reach the bundler")
                    }
                }

                else -> {
                    error("Unexpected eth_call before the bundler: $params")
                }
            }
        }

    private val widgetEngine =
        MockEngine { request ->
            assertNull(request.headers["X-API-Key"], "the public proxies take no key from the app")
            when (request.url.encodedPath) {
                "/v1/widget/public-sessions" -> {
                    sessionHost = request.url.host
                    sessionRequest = Json.parseToJsonElement(request.bodyText()).jsonObject
                    respond(
                        if (sessionStatus == HttpStatusCode.OK) {
                            """{"widget_url":"$WIDGET_URL"}"""
                        } else {
                            """{"detail":"{\"detail\":\"redirect_uri_not_allowlisted\"}"}"""
                        },
                        sessionStatus,
                        jsonHeaders,
                    )
                }

                "/v1/widget/attestation" -> {
                    redeemed = true
                    respond(ATTESTATION_BODY, attestationStatus, jsonHeaders)
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
    fun `a network with no services fails before touching the network`() =
        runTest {
            val statuses =
                driver(IdentityServices.NONE)
                    .verify(IdentityCheck.Liveness, CurrencyCode.Brl, NONCE, IdentityReturnSignal())
                    .toList()

            assertEquals(listOf(IdentityStatus.Failed(IdentityFailure.Unavailable)), statuses)
            assertNull(sessionRequest)
        }

    @Test
    fun `liveness is not offered in india, as on p2p's own client`() =
        runTest {
            assertFalse(driver().isOffered(IdentityCheck.Liveness, CurrencyCode.Inr))
            assertTrue(driver().isOffered(IdentityCheck.Passport, CurrencyCode.Inr))
            assertTrue(driver().isOffered(IdentityCheck.Liveness, CurrencyCode.Brl))

            val statuses =
                driver().verify(IdentityCheck.Liveness, CurrencyCode.Inr, NONCE, IdentityReturnSignal()).toList()

            assertEquals(listOf(IdentityStatus.Failed(IdentityFailure.Unavailable)), statuses)
            assertNull(sessionRequest)
        }

    @Test
    fun `a liveness session is opened for the smart account, with no country`() =
        runTest {
            val signal = IdentityReturnSignal().also { it.deliver(cancelled(IdentityCheck.Liveness)) }

            val statuses = driver().verify(IdentityCheck.Liveness, CurrencyCode.Brl, NONCE, signal).toList()

            // The attestation binds msg.sender, which is the smart account, never the owner key.
            val session = sessionRequest!!
            assertEquals(LIVENESS_HOST, sessionHost)
            assertEquals(smartAccount.checksumHex, session["wallet_pubkey"]?.jsonPrimitive?.content)
            assertEquals(LIVENESS_TENANT, session["tenant"]?.jsonPrimitive?.content)
            assertEquals("zcash://liveness-return", session["redirect_uri"]?.jsonPrimitive?.content)
            assertEquals("$NONCE.BRL", session["state"]?.jsonPrimitive?.content)
            assertFalse(session.containsKey("country"))
            assertEquals(
                listOf(IdentityStatus.Preparing, IdentityStatus.Ready(WIDGET_URL), IdentityStatus.Verifying),
                statuses.dropLast(1),
            )
            assertEquals(IdentityStatus.Failed(IdentityFailure.Cancelled), statuses.last())
        }

    @Test
    fun `a passport session goes to its own service and names the corridor's country`() =
        runTest {
            val signal = IdentityReturnSignal().also { it.deliver(cancelled(IdentityCheck.Passport)) }

            driver().verify(IdentityCheck.Passport, CurrencyCode.Inr, NONCE, signal).toList()

            val session = sessionRequest!!
            assertEquals(PASSPORT_HOST, sessionHost)
            assertEquals(PASSPORT_TENANT, session["tenant"]?.jsonPrimitive?.content)
            assertEquals("zcash://kyc-return", session["redirect_uri"]?.jsonPrimitive?.content)
            assertEquals("IN", session["country"]?.jsonPrimitive?.content)
        }

    @Test
    fun `a redirect the proxy has not allowlisted reads as unavailable`() =
        runTest {
            sessionStatus = HttpStatusCode.BadRequest

            val statuses =
                driver().verify(IdentityCheck.Liveness, CurrencyCode.Brl, NONCE, IdentityReturnSignal()).toList()

            assertEquals(IdentityStatus.Failed(IdentityFailure.Unavailable), statuses.last())
        }

    @Test
    fun `an error return is taken at its word`() =
        runTest {
            val outcomes =
                listOf("cancelled", "duplicate_person", "expired", "liveness_failed").map { error ->
                    val ret = IdentityReturn(IdentityCheck.Liveness, code = null, error = error, state = null)
                    val signal = IdentityReturnSignal().also { it.deliver(ret) }
                    assertIs<IdentityStatus.Failed>(
                        driver().verify(IdentityCheck.Liveness, CurrencyCode.Brl, NONCE, signal).toList().last(),
                    ).reason
                }

            assertEquals(
                listOf(
                    IdentityFailure.Cancelled,
                    IdentityFailure.AlreadyClaimed,
                    IdentityFailure.Expired,
                    IdentityFailure.NotPassed,
                ),
                outcomes,
            )
            assertFalse(redeemed, "an error return has no code to redeem")
        }

    @Test
    fun `a code with another session's state is refused unredeemed`() =
        runTest {
            val ret = IdentityReturn(IdentityCheck.Liveness, CODE, null, "other.BRL")
            val signal = IdentityReturnSignal().also { it.deliver(ret) }

            val statuses = driver().verify(IdentityCheck.Liveness, CurrencyCode.Brl, NONCE, signal).toList()

            assertEquals(IdentityFailure.Rejected, assertIs<IdentityStatus.Failed>(statuses.last()).reason)
            assertFalse(redeemed, "a foreign code is never spent on this wallet's behalf")
        }

    @Test
    fun `a return from the other check is refused unredeemed`() =
        runTest {
            val ret = IdentityReturn(IdentityCheck.Passport, CODE, null, "$NONCE.BRL")
            val signal = IdentityReturnSignal().also { it.deliver(ret) }

            val statuses = driver().verify(IdentityCheck.Liveness, CurrencyCode.Brl, NONCE, signal).toList()

            assertEquals(IdentityFailure.Rejected, assertIs<IdentityStatus.Failed>(statuses.last()).reason)
            assertFalse(redeemed)
        }

    @Test
    fun `each check submits its own function to the reputation manager, simulated first`() =
        runTest {
            nextRevert = LIVENESS_NULLIFIER_ALREADY_SPENT
            val liveness = driver().resume(success(IdentityCheck.Liveness), CurrencyCode.Brl).toList()

            assertEquals(IdentityCheck.Liveness, simulated)
            assertTrue(simulatedTo!!.contains(network.reputationManagerAddress.lowercaseHex, ignoreCase = true))
            assertEquals(listOf(IdentityStatus.Verifying, IdentityStatus.Submitting), liveness.dropLast(1))
            assertEquals(IdentityStatus.Failed(IdentityFailure.AlreadyClaimed), liveness.last())

            nextRevert = KYC_ALREADY_VERIFIED
            val passport = driver().resume(success(IdentityCheck.Passport), CurrencyCode.Brl).toList()

            assertEquals(IdentityCheck.Passport, simulated)
            assertEquals(IdentityStatus.Failed(IdentityFailure.AlreadyVerified), passport.last())
        }

    @Test
    fun `an unmapped revert is reported and read as a rejection`() =
        runTest {
            nextRevert = "0xdeadbeef"
            val reported = mutableListOf<String>()

            val failed =
                driver(onUnrecognisedRevert = reported::add)
                    .resume(success(IdentityCheck.Liveness), CurrencyCode.Brl)
                    .toList()
                    .last()

            assertEquals(IdentityStatus.Failed(IdentityFailure.Rejected), failed)
            assertEquals(listOf("0xdeadbeef"), reported)
        }

    @Test
    fun `a code the service no longer has is an expired check`() =
        runTest {
            attestationStatus = HttpStatusCode.BadRequest

            val failed = driver().resume(success(IdentityCheck.Liveness), CurrencyCode.Brl).toList().last()

            assertEquals(IdentityStatus.Failed(IdentityFailure.Expired), failed)
            assertNull(simulated)
        }

    private fun driver(
        services: IdentityServices = SERVICES,
        onUnrecognisedRevert: (String) -> Unit = {},
    ): IdentityVerificationDriver {
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
        return IdentityVerificationDriver(
            widget = IdentityWidgetClient(widgetHttp),
            services = services,
            returnUrl = {
                when (it) {
                    IdentityCheck.Liveness -> "zcash://liveness-return"
                    IdentityCheck.Passport -> "zcash://kyc-return"
                }
            },
            reputationReader = ReputationReader(rpc, network),
            submitters = submitters,
            rpc = rpc,
            network = network,
            onUnrecognisedRevert = onUnrecognisedRevert,
        )
    }

    private fun sampleAttestation() =
        IdentityAttestation(
            nullifier = ByteArray(IdentityAttestation.NULLIFIER_BYTES) { 0x11 },
            limit = bigIntegerValueOf(0L),
            expiry = bigIntegerValueOf(1_760_000_000L),
            signature = ByteArray(IdentityAttestation.SIGNATURE_BYTES) { 0xab.toByte() },
        )

    private fun cancelled(check: IdentityCheck) = IdentityReturn(check, code = null, error = "cancelled", state = null)

    private fun success(check: IdentityCheck) = IdentityReturn(check, code = CODE, error = null, state = "$NONCE.BRL")

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
        const val ENCODED_SMART_ACCOUNT =
            "0x" + "000000000000000000000000111111111111111111111111111111111111baaf"
        const val SELECTOR_BYTES = 4

        const val LIVENESS_NULLIFIER_ALREADY_SPENT = "0x61746f27"
        const val KYC_ALREADY_VERIFIED = "0x10afbce2"

        const val LIVENESS_HOST = "liveness.example"
        const val PASSPORT_HOST = "passport.example"
        const val LIVENESS_TENANT = "liveness-tenant"
        const val PASSPORT_TENANT = "passport-tenant"
        val SERVICES =
            IdentityServices(
                liveness = IdentityService("https://$LIVENESS_HOST", LIVENESS_TENANT),
                passport = IdentityService("https://$PASSPORT_HOST", PASSPORT_TENANT),
            )

        const val WIDGET_URL = "https://liveness.example/wizard?s=abc"
        const val NONCE = "3f2a9c1d8e7b6a5f4c3d2e1f0a9b8c7d"
        const val CODE = "kX9v_2Jq-7Lm3ZfQw8RtYbN4cH6sD1eA0PoIuGhVjKl"

        /** The shape p2p.me's SDK reads: flat, amounts as numbers or strings. */
        val ATTESTATION_BODY =
            """{"nullifier":"0x${"11".repeat(32)}","limit":0,"expiry":"1760000000",""" +
                """"signature":"0x${"ab".repeat(65)}","identity_hash":"h"}"""

        val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    }
}
