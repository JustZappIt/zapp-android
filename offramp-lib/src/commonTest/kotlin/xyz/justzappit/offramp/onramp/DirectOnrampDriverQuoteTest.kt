// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.onramp

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import xyz.justzappit.offramp.p2p.RelayIdentities
import xyz.justzappit.offramp.p2p.SubgraphClient
import xyz.justzappit.offramp.p2p.Usdc6
import xyz.justzappit.offramp.reputation.ReputationCalls
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The route is decided with the quote, from limits read beside the price. A refusal here lands
 * on the amount screen as a sentence; the same refusal from the chain would be a failed placement
 * after a wait. The wallet throughout is the cold-start case: no reputation, a passed selfie.
 */
class DirectOnrampDriverQuoteTest {
    private val owner: EvmKey = EvmKeyDerivation.derive(MNEMONIC, accountIndex = 0)
    private val network = P2pNetworks.SEPOLIA

    private var directMicros = 0L
    private var integratorMicros = 20_000_000L
    private var ordersRemaining = 5L
    private var integratorPaused = false

    private val getAddressSelector = ThirdwebSmartAccount.getAddressCalldata(owner.address).selector()
    private val priceSelector = DiamondCalls.getPriceConfigCalldata(CurrencyCode.Inr).selector()
    private val thresholdSelector = DiamondCalls.getSmallOrderThresholdCalldata(CurrencyCode.Inr).selector()
    private val feeSelector = DiamondCalls.getSmallOrderFixedFeeBuyCalldata(CurrencyCode.Inr).selector()
    private val exchangeStatusSelector = DiamondCalls.getExchangeStatusCalldata().selector()
    private val currencySupportedSelector = DiamondCalls.isCurrencySupportedCalldata(CurrencyCode.Inr).selector()
    private val userTxLimitSelector = ReputationCalls.userTxLimitCalldata(SMART_ACCOUNT, CurrencyCode.Inr).selector()
    private val effectiveLimitSelector = LivenessCalls.effectiveLimitCalldata(SMART_ACCOUNT).selector()
    private val remainingSelector = LivenessCalls.remainingDailyCountCalldata(SMART_ACCOUNT).selector()
    private val pausedSelector = LivenessCalls.pausedCalldata().selector()

    private val rpcHttp =
        HttpClient(
            MockEngine { request ->
                val body = Json.parseToJsonElement(request.bodyText()).jsonObject
                val calldata =
                    body
                        .getValue("params")
                        .jsonArray[0]
                        .jsonObject
                        .getValue("data")
                        .jsonPrimitive.content
                val result =
                    when (calldata.substring(0, SELECTOR_HEX_LEN)) {
                        getAddressSelector -> SMART_ACCOUNT.bytes.padLeftToWord()
                        priceSelector -> priceConfig()
                        thresholdSelector -> word(0L)
                        feeSelector -> word(0L)
                        exchangeStatusSelector -> word(1L)
                        currencySupportedSelector -> word(1L)
                        userTxLimitSelector -> word(directMicros) + word(0L)
                        effectiveLimitSelector -> word(integratorMicros)
                        remainingSelector -> word(ordersRemaining)
                        pausedSelector -> word(if (integratorPaused) 1L else 0L)
                        else -> error("Unexpected eth_call on the quote path: $calldata")
                    }
                respond(
                    """{"jsonrpc":"2.0","id":1,"result":"0x${result.toHex()}"}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        ) { install(ContentNegotiation) { json() } }
    private val bundlerHttp = HttpClient(MockEngine { error("quoting must never reach the bundler") })
    private val subgraphHttp = HttpClient(MockEngine { error("quoting must never reach the subgraph") })
    private val rpc = BaseRpcClient(rpcHttp, "http://mock/rpc")

    @AfterTest
    fun shutdown() {
        rpcHttp.close()
        bundlerHttp.close()
        subgraphHttp.close()
    }

    @Test
    fun `an amount only the selfie covers is quoted through the integrator`() =
        runTest {
            val quote = driver().quote(inr(1_500), CurrencyCode.Inr)

            assertEquals(Usdc6.ofMicros(15_000_000L), quote.netUsdc)
            assertEquals(OnrampRoute.INTEGRATOR, quote.route)
        }

    @Test
    fun `an amount the diamond covers is quoted direct, whatever the selfie says`() =
        runTest {
            directMicros = 50_000_000L

            assertEquals(OnrampRoute.DIRECT, driver().quote(inr(1_500), CurrencyCode.Inr).route)
        }

    @Test
    fun `above both limits is refused as the per-order cap, on the amount screen`() =
        runTest {
            val e = assertFailsWith<OnrampException> { driver().quote(inr(2_500), CurrencyCode.Inr) }

            assertEquals(OnrampFailureCode.CAP_EXCEEDED, e.code)
        }

    @Test
    fun `a selfie wallet out of orders for today is told so, not that it is over the cap`() =
        runTest {
            // A smaller amount would not help; only waiting does. The two sentences differ there.
            ordersRemaining = 0

            val e = assertFailsWith<OnrampException> { driver().quote(inr(1_500), CurrencyCode.Inr) }

            assertEquals(OnrampFailureCode.DAILY_LIMIT_EXCEEDED, e.code)
        }

    @Test
    fun `out of orders but with a diamond limit of its own, the refusal says a smaller amount works`() =
        runTest {
            // "Try again tomorrow" would send away a wallet that can buy $10 right now.
            ordersRemaining = 0
            directMicros = 10_000_000L

            val e = assertFailsWith<OnrampException> { driver().quote(inr(1_500), CurrencyCode.Inr) }

            assertEquals(OnrampFailureCode.CAP_EXCEEDED, e.code)
            assertEquals(OnrampRoute.DIRECT, driver().quote(inr(1_000), CurrencyCode.Inr).route)
        }

    @Test
    fun `a paused integrator is refused on the amount screen, not after a screening and a UserOp`() =
        runTest {
            integratorPaused = true

            val e = assertFailsWith<OnrampException> { driver().quote(inr(1_500), CurrencyCode.Inr) }

            assertEquals(OnrampFailureCode.ROUTE_DISABLED, e.code)
            // The limit the wallet holds is still the ceiling shown; the switch is what is off.
            assertEquals(inr(2_000), driver().limits(CurrencyCode.Inr).maxFiat)
        }

    @Test
    fun `the amount screen's ceiling is the higher of the two limits`() =
        runTest {
            val limits = driver().limits(CurrencyCode.Inr)

            // $20 at 100 INR per USDC; the Diamond's own $0 would have closed the corridor.
            assertEquals(true, limits.enabled)
            assertEquals(inr(2_000), limits.maxFiat)
        }

    // ---- harness ----

    private fun driver(): DirectOnrampDriver {
        val accountProvider = FixedAccountProvider(owner)
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
            subgraph = SubgraphClient(subgraphHttp, "http://mock/graph"),
            orderReader = UnusedOrderReadSource,
            // Non-null: a missing screening service disables the corridor before any limit is read.
            screening =
                OnrampScreeningClient(
                    httpClient = HttpClient(MockEngine { error("quoting must never screen") }),
                    config = OnrampScreeningConfig("http://mock/screening", SCREENING_KEY_HEX),
                    deviceSignals = { error("quoting must never collect device signals") },
                    nowMillis = { 0L },
                ),
            relayIdentityStore = InMemoryRelayIdentityStore(RelayIdentities.generate()),
            orderRecipientUpiCache = InMemoryOrderRecipientUpiCache(),
            nowMillis = { 0L },
        )
    }

    /** 100 INR per USDC, both ways. */
    private fun priceConfig(): ByteArray =
        AbiEncoder.encode(
            listOf(
                AbiUint(bigIntegerValueOf(BUY_PRICE_MICROS)),
                AbiUint(bigIntegerValueOf(BUY_PRICE_MICROS)),
                AbiUint(bigIntegerZero),
                AbiUint(bigIntegerZero),
            ),
        )

    private fun inr(whole: Long): Usdc6 = Usdc6.ofMicros(whole * MICROS_PER_UNIT)

    private fun word(value: Long): ByteArray = bigIntegerValueOf(value).toByteArray().padLeftToWord()

    private fun ByteArray.selector(): String = "0x" + copyOfRange(0, SELECTOR_BYTES).toHex()

    private fun io.ktor.client.request.HttpRequestData.bodyText(): String =
        (body as OutgoingContent.ByteArrayContent).bytes().decodeToString()

    private class FixedAccountProvider(
        private val key: EvmKey,
    ) : OfframpAccountProvider {
        override suspend fun nextOfframpAccount(): EvmKey = key
    }

    private data object UnusedOrderReadSource : OrderReadSource {
        override suspend fun fetchOrder(orderId: BigInteger): OrderSnapshot = error("quoting reads no order")
    }

    private companion object {
        const val MNEMONIC =
            "abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon abandon abandon about"
        const val SCREENING_KEY_HEX = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
        const val SELECTOR_BYTES = 4
        const val SELECTOR_HEX_LEN = 10
        const val MICROS_PER_UNIT = 1_000_000L
        const val BUY_PRICE_MICROS = 100_000_000L
        val SMART_ACCOUNT: Address = Address.parse("0x111111111111111111111111111111111111baaf")
    }
}
