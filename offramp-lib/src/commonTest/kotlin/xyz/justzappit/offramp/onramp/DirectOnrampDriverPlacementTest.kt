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
import xyz.justzappit.offramp.p2p.CurrencyCode
import xyz.justzappit.offramp.p2p.DiamondCalls
import xyz.justzappit.offramp.p2p.InMemoryOrderRecipientUpiCache
import xyz.justzappit.offramp.p2p.InMemoryRelayIdentityStore
import xyz.justzappit.offramp.p2p.OrderReadSource
import xyz.justzappit.offramp.p2p.OrderSnapshot
import xyz.justzappit.offramp.p2p.OrderType
import xyz.justzappit.offramp.p2p.RelayIdentities
import xyz.justzappit.offramp.p2p.SubgraphClient
import xyz.justzappit.offramp.p2p.Usdc6
import kotlin.io.encoding.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class DirectOnrampDriverPlacementTest {
    private val owner = EvmKeyDerivation.derive(MNEMONIC, accountIndex = 0)
    private val network = P2pNetworks.SEPOLIA
    private val routingCalls = mutableListOf<ByteArray>()
    private var screeningEnvelope: JsonObject? = null

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

    private val rpcHttp =
        HttpClient(
            MockEngine { request ->
                val body = request.body.jsonObject()
                val calldata =
                    body
                        .getValue("params")
                        .jsonArray[0]
                        .jsonObject
                        .getValue("data")
                        .jsonPrimitive
                        .content
                        .hexToBytes()
                val result =
                    when (calldata.selector()) {
                        getAddressSelector -> SMART_ACCOUNT.bytes.padLeftToWord()
                        getPriceSelector -> priceConfigResult()
                        getProcessingTimeSelector -> processingTimeResult()
                        getAssignableSelector -> recordRoutingCall(calldata)
                        else -> error("Unexpected placement eth_call: 0x${calldata.toHex()}")
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
                respond(
                    content = """{"approved":false,"message":"test stop"}""",
                    status = HttpStatusCode.OK,
                    headers = JSON_HEADERS,
                )
            },
        )

    private val bundlerHttp = HttpClient(MockEngine { error("rejected screening must stop before submission") })

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

    private fun decryptScreeningPayload(envelope: JsonObject): JsonObject {
        val subject = envelope.getValue("user_address").jsonPrimitive.content
        val timestamp = envelope.getValue("timestamp").jsonPrimitive.content
        val ciphertext = Base64.decode(envelope.getValue("encrypted_payload").jsonPrimitive.content)
        val key =
            CryptographyProvider.Default
                .get(AES.GCM)
                .keyDecoder()
                .decodeFromByteArrayBlocking(AES.Key.Format.RAW, SCREENING_KEY_HEX.hexToBytes())
        val plaintext = key.cipher().decryptBlocking(ciphertext, "buy_order|$subject|$timestamp".encodeToByteArray())
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
