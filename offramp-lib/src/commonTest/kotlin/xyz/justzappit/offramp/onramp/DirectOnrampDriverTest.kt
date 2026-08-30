// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.onramp

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.justzappit.evm.crypto.Ecies
import xyz.justzappit.evm.hd.EvmKey
import xyz.justzappit.evm.hd.EvmKeyDerivation
import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.math.bigIntegerOne
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
import xyz.justzappit.offramp.p2p.DiamondCalls
import xyz.justzappit.offramp.p2p.InMemoryOrderRecipientUpiCache
import xyz.justzappit.offramp.p2p.InMemoryRelayIdentityStore
import xyz.justzappit.offramp.p2p.OrderReadSource
import xyz.justzappit.offramp.p2p.OrderSnapshot
import xyz.justzappit.offramp.p2p.OrderStatus
import xyz.justzappit.offramp.p2p.OrderType
import xyz.justzappit.offramp.p2p.RelayIdentities
import xyz.justzappit.offramp.p2p.SubgraphClient
import xyz.justzappit.offramp.p2p.Usdc6
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The tail of a BUY: what the driver decides when the wait ends.
 *
 * Every case here is about one question — does the checkpoint survive? The checkpoint is what pays
 * the ZEC out later, so a wait that ends in the wrong branch either orphans a live on-chain order
 * or keeps polling one that is already dead. `resume()` is the entry point used throughout because
 * it reaches the same [DirectOnrampDriver.watch] loop as `start()` without going through placement,
 * which needs a bundler, a screening service and a merchant circle to say anything at all.
 */
class DirectOnrampDriverTest {
    private val owner: EvmKey = EvmKeyDerivation.derive(MNEMONIC, accountIndex = 0)
    private val network = P2pNetworks.SEPOLIA

    /** Selectors, taken from the real calldata builders so a signature change fails here first. */
    private val getAddressSelector = ThirdwebSmartAccount.getAddressCalldata(owner.address).selector()
    private val expiresAtSelector = DiamondCalls.getOrderExpiresAtCalldata(bigIntegerOne).selector()
    private val isOrderExpiredSelector = DiamondCalls.isOrderExpiredCalldata(bigIntegerOne).selector()
    private val additionalDetailsSelector = DiamondCalls.getAdditionalOrderDetailsCalldata(bigIntegerOne).selector()

    private var isOrderExpired = ENCODED_ZERO
    private var additionalDetails = ENCODED_DETAILS_ALL_ZERO
    private var rpcIsDown = false

    /** A Diamond revert selector to answer the next eth_call with, as the chain would. */
    private var nextRevert: String? = null

    private val rpcEngine =
        MockEngine { request ->
            if (rpcIsDown) {
                respond("""{"error":"upstream is having a day"}""", HttpStatusCode.BadGateway, jsonHeaders)
            } else if (nextRevert != null) {
                // A revert is an HTTP 200 carrying a JSON-RPC error, not an HTTP failure.
                respond(
                    """{"jsonrpc":"2.0","id":1,"error":{"code":3,"message":"execution reverted","data":"$nextRevert"}}""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
            } else {
                val bytes = (request.body as io.ktor.http.content.OutgoingContent.ByteArrayContent).bytes()
                val payload = Json.parseToJsonElement(bytes.decodeToString()) as JsonObject
                val method = payload["method"]!!.jsonPrimitive.content
                val params = payload["params"].toString()
                val result =
                    when {
                        method != "eth_call" -> error("Unexpected RPC method on the watch path: $method")

                        // The counterfactual smart-account address, from the factory.
                        getAddressSelector in params -> ENCODED_SMART_ACCOUNT

                        isOrderExpiredSelector in params -> isOrderExpired

                        additionalDetailsSelector in params -> additionalDetails

                        expiresAtSelector in params -> ENCODED_ZERO

                        else -> error("Unexpected eth_call on the watch path: $params")
                    }
                respond("""{"jsonrpc":"2.0","id":1,"result":"$result"}""", HttpStatusCode.OK, jsonHeaders)
            }
        }

    private val rpcHttp = HttpClient(rpcEngine) { install(ContentNegotiation) { json() } }
    private val bundlerHttp = HttpClient(MockEngine { error("the watch path must never reach the bundler") })
    private val subgraphHttp = HttpClient(MockEngine { error("the watch path must never reach the subgraph") })

    private val rpc = BaseRpcClient(rpcHttp, "http://mock/rpc")
    private val orderReader = FixedOrderReadSource()

    /** A real relay key: the merchant handle is ECIES-sealed to it, exactly as the chain stores it. */
    private val relay = RelayIdentities.generate()

    @AfterTest
    fun shutdown() {
        rpcHttp.close()
        bundlerHttp.close()
        subgraphHttp.close()
    }

    @Test
    fun `a wait in which every poll failed must not discard the order`() =
        runTest {
            // Nothing was learned about the order, so the one thing the driver may not do is
            // decide it is gone.
            orderReader.answer = null

            val failed = assertIs<OnrampStatus.Failed>(driver().resume(checkpoint()).toList().last())

            assertEquals(OnrampFailureCode.NETWORK_UNAVAILABLE, failed.code)
            assertEquals(OnrampPhase.AWAITING_MERCHANT, failed.phase)
            assertTrue(failed.leavesOrderAlive, "a wait that learned nothing must keep the checkpoint")
        }

    @Test
    fun `a paid order that outlasts the settle window is pending, not abandoned`() =
        runTest {
            // The user's fiat has moved. The merchant is late, not absent.
            orderReader.answer = snapshot(OrderStatus.PAID)

            val statuses = driver().resume(checkpoint()).toList()
            val failed = assertIs<OnrampStatus.Failed>(statuses.last())

            assertEquals(OnrampFailureCode.SETTLEMENT_PENDING, failed.code)
            assertEquals(OnrampPhase.AWAITING_SETTLEMENT, failed.phase)
            assertTrue(failed.leavesOrderAlive, "a paid order outlives the screen that was watching it")
            assertTrue(statuses.any { it is OnrampStatus.AwaitingSettlement })
        }

    @Test
    fun `expiry is the chain's answer, not the poll count`() =
        runTest {
            // A keeper sweeps expired orders, so `placed` can outlast our clock and still be live —
            // and can equally have flipped without us seeing it.
            orderReader.answer = snapshot(OrderStatus.PLACED)
            isOrderExpired = ENCODED_ONE

            val failed = assertIs<OnrampStatus.Failed>(driver().resume(checkpoint()).toList().last())

            assertEquals(OnrampFailureCode.ORDER_EXPIRED, failed.code)
        }

    @Test
    fun `no merchant is the only ending allowed to forget the order`() =
        runTest {
            orderReader.answer = snapshot(OrderStatus.PLACED)
            isOrderExpired = ENCODED_ZERO

            val failed = assertIs<OnrampStatus.Failed>(driver().resume(checkpoint()).toList().last())

            assertEquals(OnrampFailureCode.NO_MERCHANT, failed.code)
            // The only branch of the four where nothing of the user's has moved, and so the only
            // one that may drop the checkpoint.
            assertFalse(failed.leavesOrderAlive)
        }

    @Test
    fun `a completed order reports what settled, not what was placed`() =
        runTest {
            orderReader.answer = snapshot(OrderStatus.COMPLETED)
            additionalDetails = ENCODED_DETAILS_SETTLED

            val completed = assertIs<OnrampStatus.Completed>(driver().resume(checkpoint()).toList().last())

            // The order tuple carries the placed figures; the settled ones live in their own read.
            assertEquals(SETTLED_USDC_MICROS, completed.netUsdc.micros.toString())
            assertEquals(SETTLED_FIAT_MICROS, completed.fiatAmount.micros.toString())
            assertEquals(SMART_ACCOUNT.lowercase(), completed.recipientAddress.lowercaseHex)
            // Only confirmPaid holds the payment hash. A cold-start resume never sent it and must
            // not invent one, so the receipt honestly offers no explorer link on this path.
            assertNull(completed.paidTx)
        }

    @Test
    fun `a failure before the order id is known arrives as a status, not a thrown flow`() =
        runTest {
            // Resolving the smart account is the first RPC of the resume path. If it throws and the
            // flow is not guarded, the screen sees a crash instead of an error it can offer a retry on.
            rpcIsDown = true

            val statuses = driver().resume(checkpoint()).toList()

            val failed = assertIs<OnrampStatus.Failed>(statuses.single())
            assertEquals(OnrampFailureCode.UPSTREAM_FAILED, failed.code)
            assertEquals(ORDER_ID.toString(), failed.orderId)
        }

    @Test
    fun `a rolling daily cap is told apart from a per-order one`() =
        runTest {
            // Both are "too much", but only one is fixed by entering a smaller number, so they
            // must not collapse into the same sentence.
            nextRevert = DAILY_BUY_ORDER_LIMIT_EXCEEDED

            val failed = assertIs<OnrampStatus.Failed>(driver().resume(checkpoint()).toList().single())

            assertEquals(OnrampFailureCode.DAILY_LIMIT_EXCEEDED, failed.code)
            assertFalse(failed.leavesOrderAlive, "a capped order is not coming back on its own")
        }

    @Test
    fun `a blocked wallet is named, not reported as an upstream failure`() =
        runTest {
            nextRevert = USER_IS_BLACKLISTED

            val failed = assertIs<OnrampStatus.Failed>(driver().resume(checkpoint()).toList().single())

            assertEquals(OnrampFailureCode.USER_BLACKLISTED, failed.code)
        }

    @Test
    fun `a selector this build has never seen still reaches the user as a status`() =
        runTest {
            // The catch-all stays transient: an unknown revert says nothing about whether the
            // order survived, so the checkpoint has to.
            nextRevert = "0xdeadbeef"

            val failed = assertIs<OnrampStatus.Failed>(driver().resume(checkpoint()).toList().single())

            assertEquals(OnrampFailureCode.UPSTREAM_FAILED, failed.code)
            assertTrue(failed.leavesOrderAlive)
        }

    @Test
    fun `an accepted order hands over the merchant handle and stops for the user`() =
        runTest {
            orderReader.answer = snapshot(OrderStatus.ACCEPTED, encryptedUserUpi = sealed(VPA))

            val statuses = driver().resume(checkpoint()).toList()

            val awaiting = assertIs<OnrampStatus.AwaitingPayment>(statuses.single())
            val upi = assertIs<OnrampPaymentInstruction.Upi>(awaiting.instruction)
            assertEquals(VPA, upi.address)
            // A resting state: the next move is the user's, so polling must stop here.
        }

    @Test
    fun `a resumed order whose corridor will not decode is never paid over UPI`() =
        runTest {
            // ☠ The regression. MEX is a real p2p market Zapp does not serve, so its currency word
            // decodes to nothing — and an unreadable word used to default to INR, which is the one
            // branch that wraps the handle in a upi:// intent and stamps a currency on it. For an
            // EMVCo corridor the "handle" is a whole QR payload, so that is a payment instruction
            // to the wrong rail in the wrong currency.
            orderReader.answer =
                snapshot(
                    OrderStatus.ACCEPTED,
                    currencyHex = UNSERVED_CORRIDOR_BYTES32,
                    encryptedUserUpi = sealed(EMVCO_PAYLOAD),
                )

            val awaiting = assertIs<OnrampStatus.AwaitingPayment>(driver().resume(checkpoint()).toList().single())

            val plain = assertIs<OnrampPaymentInstruction.Plain>(awaiting.instruction)
            assertEquals(EMVCO_PAYLOAD, plain.address)
        }

    @Test
    fun `an order whose handle cannot be decrypted fails rather than showing a placeholder`() =
        runTest {
            // The relay key is the only way to read it. Losing it means the order cannot be paid,
            // and a partial address would send real money to nobody.
            orderReader.answer = snapshot(OrderStatus.ACCEPTED, encryptedUserUpi = "not-a-cipher")

            val failed = assertIs<OnrampStatus.Failed>(driver().resume(checkpoint()).toList().single())

            assertEquals(OnrampFailureCode.UPSTREAM_FAILED, failed.code)
        }

    // ---- harness ----

    /** Seals [plaintext] to the relay key, the way the merchant does when accepting. */
    private fun sealed(plaintext: String): String =
        Ecies.cipherStringify(Ecies.encryptWithPublicKey(relay.publicKeyHex, plaintext))

    /**
     * Poll intervals of zero and a cap of two keep the timeout branches reachable in a test; these
     * four parameters exist for exactly this and have no production caller.
     */
    private fun driver(): DirectOnrampDriver {
        val smartAccounts =
            SmartOfframpAccountProvider(
                accountProvider = FixedAccountProvider(owner),
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
            accountProvider = FixedAccountProvider(owner),
            subgraph = SubgraphClient(subgraphHttp, "http://mock/graph"),
            orderReader = orderReader,
            screening = null,
            relayIdentityStore = InMemoryRelayIdentityStore(relay),
            orderRecipientUpiCache = InMemoryOrderRecipientUpiCache(),
            nowMillis = { 0L },
            acceptPollMillis = 0,
            settlePollMillis = 0,
            acceptPollAttempts = 2,
            settlePollAttempts = 2,
        )
    }

    private fun checkpoint() =
        OnrampCheckpoint(
            id = ORDER_ID.toString(),
            phase = OnrampPhase.AWAITING_MERCHANT,
            orderId = ORDER_ID.toString(),
        )

    private fun snapshot(
        status: OrderStatus,
        currencyHex: String = INR_BYTES32,
        encryptedUserUpi: String = "",
    ) = OrderSnapshot(
        orderId = ORDER_ID,
        status = status,
        orderType = OrderType.BUY,
        circleId = bigIntegerOne,
        userAddress = Address.parse(SMART_ACCOUNT),
        usdcAmount = Usdc6.ofMicros(5_000_000),
        fiatAmount = Usdc6.ofMicros(445_000_000),
        currencyHex = currencyHex,
        acceptedMerchantAddress = null,
        merchantPubKey = "",
        encryptedUserUpi = encryptedUserUpi,
        encryptedMerchantUpi = "",
        placedAtEpochSeconds = 1_779_000_000L,
        acceptedAtEpochSeconds = null,
        paidAtEpochSeconds = null,
        completedAtEpochSeconds = null,
        cancelledAtEpochSeconds = null,
        actualUsdcAmount = null,
        actualFiatAmount = null,
        placedTxHash = null,
        source = OrderSnapshot.Source.OnChain,
    )

    private fun ByteArray.selector(): String = copyOfRange(0, SELECTOR_BYTES).toHex()

    /** Answers every poll the same way, so a test states one condition rather than a script. */
    private class FixedOrderReadSource : OrderReadSource {
        var answer: OrderSnapshot? = null

        override suspend fun fetchOrder(orderId: BigInteger): OrderSnapshot? = answer
    }

    private class FixedAccountProvider(
        private val key: EvmKey,
    ) : OfframpAccountProvider {
        override suspend fun nextOfframpAccount(): EvmKey = key
    }

    private companion object {
        const val MNEMONIC =
            "abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon abandon abandon about"

        val ORDER_ID: BigInteger = bigIntegerValueOf(7)
        const val SMART_ACCOUNT = "0x111111111111111111111111111111111111baaf"
        const val SELECTOR_BYTES = 4
        const val WORD_BYTES = 32
        const val DETAILS_WORDS = 7

        val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

        const val INR_BYTES32 = "0x494e520000000000000000000000000000000000000000000000000000000000"

        /** "MEX", NUL-padded: a real p2p market this app deliberately does not carry. */
        const val UNSERVED_CORRIDOR_BYTES32 =
            "0x4d45580000000000000000000000000000000000000000000000000000000000"

        const val VPA = "merchant@upi"

        /** What an EMVCo corridor puts in the handle field: a whole QR string, not an address. */
        const val EMVCO_PAYLOAD =
            "00020101021226580014BR.GOV.BCB.PIX0136abc-def5204000053039865802BR6304A1B2"

        /** p2p.me's `DailyBuyOrderLimitExceeded`. */
        const val DAILY_BUY_ORDER_LIMIT_EXCEEDED = "0xe595a7bf"

        /** p2p.me's `UserIsBlacklisted`. */
        const val USER_IS_BLACKLISTED = "0xebb6f34b"

        const val ENCODED_ZERO = "0x" + "0000000000000000000000000000000000000000000000000000000000000000"
        const val ENCODED_ONE = "0x" + "0000000000000000000000000000000000000000000000000000000000000001"

        /** One address word: the factory's `getAddress` answer. */
        const val ENCODED_SMART_ACCOUNT =
            "0x" + "000000000000000000000000111111111111111111111111111111111111baaf"

        const val SETTLED_USDC_MICROS = "2000000"
        const val SETTLED_FIAT_MICROS = "500000000"

        /** `getAdditionalOrderDetails` before the merchant completes: every amount still zero. */
        val ENCODED_DETAILS_ALL_ZERO = "0x" + "00".repeat(WORD_BYTES * DETAILS_WORDS)

        /**
         * The same 7-word tuple with slot 5 (`actualUsdcAmount`) = 2 USDC and slot 6
         * (`actualFiatAmount`) = 500 units, both different from the snapshot's placed figures so a
         * test can tell which read the receipt came from.
         */
        const val ENCODED_DETAILS_SETTLED =
            "0x" +
                "0000000000000000000000000000000000000000000000000000000000000000" +
                "0000000000000000000000000000000000000000000000000000000000000000" +
                "0000000000000000000000000000000000000000000000000000000000000000" +
                "0000000000000000000000000000000000000000000000000000000000000000" +
                "0000000000000000000000000000000000000000000000000000000000000000" +
                "00000000000000000000000000000000000000000000000000000000001e8480" +
                "000000000000000000000000000000000000000000000000000000001dcd6500"
    }
}
