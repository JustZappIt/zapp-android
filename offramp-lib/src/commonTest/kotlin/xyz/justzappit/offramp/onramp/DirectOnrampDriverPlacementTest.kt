// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.onramp

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.AES
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import xyz.justzappit.evm.abi.AbiAddress
import xyz.justzappit.evm.abi.AbiEncoder
import xyz.justzappit.evm.abi.AbiUint
import xyz.justzappit.evm.hd.EvmKey
import xyz.justzappit.evm.hd.EvmKeyDerivation
import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.math.bigIntegerValueOf
import xyz.justzappit.evm.math.bigIntegerZero
import xyz.justzappit.evm.rpc.BaseRpcClient
import xyz.justzappit.evm.rpc.BundlerClient
import xyz.justzappit.evm.signer.ThirdwebSmartAccount
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.util.hexToBytes
import xyz.justzappit.evm.util.padLeftToWord
import xyz.justzappit.evm.util.toHex
import xyz.justzappit.offramp.account.Erc4337SubmitterProvider
import xyz.justzappit.offramp.account.OfframpAccountProvider
import xyz.justzappit.offramp.account.SmartOfframpAccountProvider
import xyz.justzappit.offramp.config.P2pNetworks
import xyz.justzappit.offramp.liveness.LivenessCalls
import xyz.justzappit.offramp.p2p.CurrencyCode
import xyz.justzappit.offramp.p2p.DiamondCalls
import xyz.justzappit.offramp.p2p.InMemoryOrderRecipientUpiCache
import xyz.justzappit.offramp.p2p.InMemoryRelayIdentityStore
import xyz.justzappit.offramp.p2p.OrderReadSource
import xyz.justzappit.offramp.p2p.OrderSnapshot
import xyz.justzappit.offramp.p2p.OrderType
import xyz.justzappit.offramp.p2p.PlaceOrderArgs
import xyz.justzappit.offramp.p2p.RelayIdentities
import xyz.justzappit.offramp.p2p.SubgraphClient
import xyz.justzappit.offramp.p2p.Usdc6
import kotlin.io.encoding.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DirectOnrampDriverPlacementTest {
    private val owner = EvmKeyDerivation.derive(MNEMONIC, accountIndex = 0)
    private val network = P2pNetworks.SEPOLIA
    private val routingCalls = mutableListOf<ByteArray>()
    private var screeningEnvelope: JsonObject? = null
    private var screeningPath: String? = null
    private var screeningApproves = false

    /** The `callData` of the first UserOp the bundler sees, at which point the mock stops the run. */
    private var submittedCallData: String? = null

    private val getAddressSelector = ThirdwebSmartAccount.getAddressCalldata(owner.address).selector()
    private val getPriceSelector = DiamondCalls.getPriceConfigCalldata(CurrencyCode.Inr).selector()
    private val getProcessingTimeSelector = DiamondCalls.getProcessingTimeCalldata().selector()
    private val getAssignableSelector =
        DiamondCalls
            .getAssignableMerchantsFromCircleCalldata(
                circleId = CIRCLE_ID,
                assignUpTo = ASSIGN_UP_TO,
                currency = CurrencyCode.Inr,
                user = SMART_ACCOUNT,
                usdtAmount = QUOTE.netUsdc,
                fiatAmount = QUOTE.fiatAmount,
                orderType = OrderType.BUY,
            ).selector()
    private val getNonceSelector =
        AbiEncoder
            .encodeFunctionCall("getNonce(address,uint192)", listOf(AbiAddress(SMART_ACCOUNT), AbiUint(bigIntegerZero)))
            .selector()

    private val rpcHttp =
        HttpClient(
            MockEngine { request ->
                val body = request.body.jsonObject()
                val result =
                    when (body.getValue("method").jsonPrimitive.content) {
                        // A deployed account, so the UserOp carries no initCode.
                        "eth_getCode" -> DEPLOYED_CODE
                        "eth_call" -> {
                            val calldata =
                                body
                                    .getValue("params")
                                    .jsonArray[0]
                                    .jsonObject
                                    .getValue("data")
                                    .jsonPrimitive
                                    .content
                                    .hexToBytes()
                            when (calldata.selector()) {
                                getAddressSelector -> SMART_ACCOUNT.bytes.padLeftToWord()
                                getPriceSelector -> priceConfigResult()
                                getProcessingTimeSelector -> processingTimeResult()
                                getAssignableSelector -> recordRoutingCall(calldata)
                                getNonceSelector -> ByteArray(WORD_BYTES)
                                else -> error("Unexpected placement eth_call: 0x${calldata.toHex()}")
                            }
                        }

                        else -> error("Unexpected RPC method on the placement path: $body")
                    }
                respond(
                    content = """{"jsonrpc":"2.0","id":1,"result":"0x${result.toHex()}"}""",
                    status = HttpStatusCode.OK,
                    headers = JSON_HEADERS,
                )
            },
        ) { install(ContentNegotiation) { json() } }

    private val subgraphHttp =
        HttpClient(
            MockEngine {
                respond(
                    content = circleResponse(),
                    status = HttpStatusCode.OK,
                    headers = JSON_HEADERS,
                )
            },
        ) { install(ContentNegotiation) { json() } }

    private val screeningHttp =
        HttpClient(
            MockEngine { request ->
                screeningEnvelope = request.body.jsonObject()
                screeningPath = request.url.encodedPath
                respond(
                    content =
                        if (screeningApproves) {
                            """{"approved":true,"activity_log_id":"a-1"}"""
                        } else {
                            """{"approved":false,"message":"test stop"}"""
                        },
                    status = HttpStatusCode.OK,
                    headers = JSON_HEADERS,
                )
            },
        )

    /**
     * Answers the gas-price read, then captures the UserOp the paymaster stub is asked for — the
     * first call that carries `callData` — and stops the run there. Nothing is ever submitted.
     */
    private val bundlerHttp =
        HttpClient(
            MockEngine { request ->
                val body = request.body.jsonObject()
                when (body.getValue("method").jsonPrimitive.content) {
                    "pimlico_getUserOperationGasPrice" ->
                        respond(
                            content = """{"jsonrpc":"2.0","id":1,"result":{"standard":""" +
                                """{"maxFeePerGas":"0x1","maxPriorityFeePerGas":"0x1"}}}""",
                            status = HttpStatusCode.OK,
                            headers = JSON_HEADERS,
                        )

                    "pm_getPaymasterStubData" -> {
                        submittedCallData =
                            body
                                .getValue("params")
                                .jsonArray[0]
                                .jsonObject
                                .getValue("callData")
                                .jsonPrimitive
                                .content
                        error("captured the UserOp; nothing past this point is under test")
                    }

                    else -> error("rejected screening must stop before submission: $body")
                }
            },
        ) { install(ContentNegotiation) { json() } }

    @AfterTest
    fun closeClients() {
        rpcHttp.close()
        subgraphHttp.close()
        screeningHttp.close()
        bundlerHttp.close()
    }

    @Test
    fun `placement routes and screens with the quoted fiat amount and INR metadata`() =
        runTest {
            val statuses = driver().start(QUOTE).toList()

            val failed = assertIs<OnrampStatus.Failed>(statuses.last())
            assertEquals(OnrampFailureCode.SCREENING_REJECTED, failed.code)

            val fiatAmountLimit = DirectOnrampPricing.fiatAmountLimit(QUOTE.netUsdc, QUOTE.buyPrice)
            assertNotEquals(QUOTE.fiatAmount, fiatAmountLimit)
            assertContentEquals(expectedRoutingCall(QUOTE.fiatAmount), routingCalls.single())
            assertNotEquals(expectedRoutingCall(fiatAmountLimit).toList(), routingCalls.single().toList())

            val payload = decryptScreeningPayload(checkNotNull(screeningEnvelope))
            val transaction = payload.getValue("transaction_details").jsonObject
            val user = payload.getValue("user_details").jsonObject
            assertEquals("539.26", transaction.getValue("fiat_amount").jsonPrimitive.content)
            assertEquals("INR", transaction.getValue("currency").jsonPrimitive.content)
            assertEquals("UPI", transaction.getValue("payment_method").jsonPrimitive.content)
            assertEquals("1-3 minutes", transaction.getValue("estimated_processing_time").jsonPrimitive.content)
            assertEquals("India", user.getValue("country").jsonPrimitive.content)
        }

    @Test
    fun `an integrator order is screened on the b2b intake and placed on the integrator`() =
        runTest {
            // ☠ Two things change with the route, and only two. The consumer intake would score
            // this wallet as a new account and refuse it; the Diamond would revert it for having
            // no reputation. Everything else — circle, fiat cap, receipt — is the direct path's.
            screeningApproves = true

            val statuses = driver().start(QUOTE.copy(route = OnrampRoute.INTEGRATOR)).toList()

            assertEquals("/screening/activity-logs/b2b-buy-order", screeningPath)
            val envelope = checkNotNull(screeningEnvelope)
            assertFalse(envelope.containsKey("type"))
            // Decrypts under the B2B AAD: the wrong one would throw here.
            val payload = decryptScreeningPayload(envelope, aadPrefix = "b2b_buy_order")
            val transaction = payload.getValue("transaction_details").jsonObject
            assertEquals("539.26", transaction.getValue("fiat_amount").jsonPrimitive.content)
            assertEquals(OnrampScreeningConfig.DEFAULT_B2B_DOMAIN, payload.getValue("domain").jsonPrimitive.content)

            // The UserOp wraps `execute(to, value, data)`: `to` is the integrator, `data` is buyUsdc.
            val callData = assertNotNull(submittedCallData, "the run must reach the bundler")
            val executeArgs = callData.removePrefix("0x").drop(SELECTOR_HEX)
            assertEquals(
                P2pNetworks.SEPOLIA_LIVENESS_INTEGRATOR.lowercase().removePrefix("0x"),
                executeArgs.take(WORD_HEX).takeLast(ADDRESS_HEX),
            )
            val inner = executeArgs.drop(WORD_HEX * EXECUTE_HEAD_WORDS)
            assertTrue(inner.startsWith("88662523"), "buyUsdc must be the inner call, got ${inner.take(SELECTOR_HEX)}")
            assertContentEquals(expectedRoutingCall(QUOTE.fiatAmount), routingCalls.first())
            // The mock stops the run at the bundler; what matters is that it got there.
            assertEquals(OnrampFailureCode.UPSTREAM_FAILED, assertIs<OnrampStatus.Failed>(statuses.last()).code)
        }

    @Test
    fun `a direct order stays on the consumer intake and the diamond`() =
        runTest {
            screeningApproves = true

            driver().start(QUOTE).toList()

            assertEquals("/screening/activity-logs", screeningPath)
            assertEquals("buy_order", checkNotNull(screeningEnvelope).getValue("type").jsonPrimitive.content)
            val executeArgs = assertNotNull(submittedCallData).removePrefix("0x").drop(SELECTOR_HEX)
            assertEquals(
                network.diamondAddress.lowercaseHex.removePrefix("0x"),
                executeArgs.take(WORD_HEX).takeLast(ADDRESS_HEX),
            )
            val placeOrderSelector = DiamondCalls.placeOrderCalldata(anyPlaceOrder()).selector()
            assertTrue(executeArgs.drop(WORD_HEX * EXECUTE_HEAD_WORDS).startsWith(placeOrderSelector))
        }

    private fun anyPlaceOrder() =
        PlaceOrderArgs(
            relayPubKeyEthCrypto = "",
            usdcAmount = QUOTE.netUsdc,
            recipientAddress = SMART_ACCOUNT,
            orderType = OrderType.BUY,
            currency = CurrencyCode.Inr,
            circleId = CIRCLE_ID,
        )

    private fun driver(): DirectOnrampDriver {
        val accountProvider = FixedAccountProvider(owner)
        val rpc = BaseRpcClient(rpcHttp, "http://mock/rpc")
        val smartAccounts =
            SmartOfframpAccountProvider(
                accountProvider = accountProvider,
                rpc = rpc,
                accountFactory = network.accountFactoryAddress,
            )
        return DirectOnrampDriver(
            rpc = rpc,
            network = network,
            submitters =
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
                ),
            accountProvider = accountProvider,
            subgraph = SubgraphClient(subgraphHttp, "http://mock/subgraph"),
            orderReader = UnusedOrderReadSource,
            screening =
                OnrampScreeningClient(
                    httpClient = screeningHttp,
                    config = OnrampScreeningConfig("http://mock/screening", SCREENING_KEY_HEX),
                    deviceSignals = { DEVICE_SIGNALS },
                    nowMillis = { NOW_MILLIS },
                ),
            relayIdentityStore = InMemoryRelayIdentityStore(RelayIdentities.generate()),
            orderRecipientUpiCache = InMemoryOrderRecipientUpiCache(),
            nowMillis = { NOW_MILLIS },
        )
    }

    private fun expectedRoutingCall(fiatAmount: Usdc6): ByteArray =
        DiamondCalls.getAssignableMerchantsFromCircleCalldata(
            circleId = CIRCLE_ID,
            assignUpTo = ASSIGN_UP_TO,
            currency = CurrencyCode.Inr,
            user = SMART_ACCOUNT,
            usdtAmount = QUOTE.netUsdc,
            fiatAmount = fiatAmount,
            orderType = OrderType.BUY,
        )

    private fun decryptScreeningPayload(envelope: JsonObject, aadPrefix: String = "buy_order"): JsonObject {
        val subject = envelope.getValue("user_address").jsonPrimitive.content
        val timestamp = envelope.getValue("timestamp").jsonPrimitive.content
        val ciphertext = Base64.decode(envelope.getValue("encrypted_payload").jsonPrimitive.content)
        val key =
            CryptographyProvider.Default
                .get(AES.GCM)
                .keyDecoder()
                .decodeFromByteArrayBlocking(AES.Key.Format.RAW, SCREENING_KEY_HEX.hexToBytes())
        val plaintext = key.cipher().decryptBlocking(ciphertext, "$aadPrefix|$subject|$timestamp".encodeToByteArray())
        return Json.parseToJsonElement(plaintext.decodeToString()).jsonObject
    }

    private fun priceConfigResult(): ByteArray =
        AbiEncoder.encode(
            listOf(
                AbiUint(QUOTE.buyPrice.micros),
                AbiUint(QUOTE.buyPrice.micros),
                AbiUint(bigIntegerZero),
                AbiUint(bigIntegerZero),
            ),
        )

    private fun processingTimeResult(): ByteArray =
        AbiEncoder.encode(
            listOf(
                AbiUint(bigIntegerValueOf(60)),
                AbiUint(bigIntegerValueOf(180)),
                AbiUint(bigIntegerZero),
                AbiUint(bigIntegerZero),
            ),
        )

    private fun recordRoutingCall(calldata: ByteArray): ByteArray {
        routingCalls += calldata
        return ASSIGNABLE_MERCHANT_RESULT.hexToBytes()
    }

    private fun circleResponse(): String {
        val currency = "0x" + AbiEncoder.bytes32String(CurrencyCode.Inr.code).value.toHex()
        return buildJsonObject {
            putJsonObject("data") {
                putJsonArray("circles") {
                    addJsonObject {
                        put("circleId", CIRCLE_ID.toString())
                        put("currency", currency)
                        putJsonObject("metrics") {
                            put("circleScore", "100")
                            put("circleStatus", "active")
                            putJsonObject("scoreState") {
                                put("activeMerchantsCount", "1")
                            }
                        }
                    }
                }
            }
        }.toString()
    }

    private fun OutgoingContent.jsonObject(): JsonObject {
        val bytes = (this as OutgoingContent.ByteArrayContent).bytes()
        return Json.parseToJsonElement(bytes.decodeToString()).jsonObject
    }

    private fun ByteArray.selector(): String = copyOfRange(0, SELECTOR_BYTES).toHex()

    private class FixedAccountProvider(
        private val key: EvmKey,
    ) : OfframpAccountProvider {
        override suspend fun nextOfframpAccount(): EvmKey = key
    }

    private data object UnusedOrderReadSource : OrderReadSource {
        override suspend fun fetchOrder(orderId: BigInteger): OrderSnapshot = error("screening rejection stops polling")
    }

    private companion object {
        const val MNEMONIC =
            "abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon abandon abandon about"
        const val SCREENING_KEY_HEX =
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
        const val NOW_MILLIS = 1_756_450_000_123L
        const val SELECTOR_BYTES = 4
        const val SELECTOR_HEX = 8
        const val WORD_BYTES = 32
        const val WORD_HEX = 64
        const val ADDRESS_HEX = 40

        /** `execute(address,uint256,bytes)`: to, value, the bytes' offset word, then its length word. */
        const val EXECUTE_HEAD_WORDS = 4

        /** Any non-empty code: the account is deployed. */
        val DEPLOYED_CODE: ByteArray = byteArrayOf(0x60.toByte(), 0x80.toByte())

        val CIRCLE_ID: BigInteger = bigIntegerValueOf(7)
        val ASSIGN_UP_TO: BigInteger = bigIntegerValueOf(3)
        val SMART_ACCOUNT: Address = Address.parse("0x111111111111111111111111111111111111baaf")
        val QUOTE =
            OnrampQuote(
                quoteId = "quote-regression",
                currency = CurrencyCode.Inr,
                fiatAmount = Usdc6.ofMicros(539_260_000),
                grossUsdc = Usdc6.ofMicros(5_123_456),
                feeUsdc = Usdc6.ofMicros(50_000),
                netUsdc = Usdc6.ofMicros(5_073_456),
                buyPrice = Usdc6.ofMicros(100_000_000),
                expiresAtMillis = NOW_MILLIS + 90_000,
            )

        const val ASSIGNABLE_MERCHANT_RESULT =
            "0000000000000000000000000000000000000000000000000000000000000020" +
                "0000000000000000000000000000000000000000000000000000000000000001" +
                "000000000000000000000000111111111111111111111111111111111111baaf"

        val JSON_HEADERS = headersOf(HttpHeaders.ContentType, "application/json")

        val DEVICE_SIGNALS =
            OnrampDeviceSignals(
                userAgent = "Zapp/Test",
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
                appVersion = "test",
                colorDepth = 24,
                pixelDepth = 24,
            )
    }
}
