// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.orchestrator

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.justzappit.evm.abi.keccak256
import xyz.justzappit.evm.hd.EvmKeyDerivation
import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.math.bigIntegerOne
import xyz.justzappit.evm.math.bigIntegerValueOf
import xyz.justzappit.evm.rpc.BaseRpcClient
import xyz.justzappit.evm.signer.EoaSigner
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.util.hexToBytes
import xyz.justzappit.evm.util.toHex
import xyz.justzappit.offramp.config.P2pNetworks
import xyz.justzappit.offramp.funding.FundingOutcome
import xyz.justzappit.offramp.funding.OfframpFunding
import xyz.justzappit.offramp.funding.OfframpRefund
import xyz.justzappit.offramp.funding.OfframpTopUp
import xyz.justzappit.offramp.funding.RefundResume
import xyz.justzappit.offramp.p2p.CircleRouter
import xyz.justzappit.offramp.p2p.CurrencyCode
import xyz.justzappit.offramp.p2p.OrderEvents
import xyz.justzappit.offramp.p2p.OrderReadSource
import xyz.justzappit.offramp.p2p.OrderSnapshot
import xyz.justzappit.offramp.p2p.OrderStatus
import xyz.justzappit.offramp.p2p.OrderType
import xyz.justzappit.offramp.p2p.SubgraphClient
import xyz.justzappit.offramp.p2p.Usdc6
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OfframpOrchestratorTest {
    private val account = EvmKeyDerivation.derive(MNEMONIC, accountIndex = 0)
    private val network = P2pNetworks.SEPOLIA

    private val rpcRequestLog = mutableListOf<String>()
    private val rawTxLog = mutableListOf<String>()
    private val rawTxHashes = mutableListOf<String>()
    private var getAssignableResponse = ENCODED_ADDRESS_ARRAY_OF_ONE

    // Raw params of every getAssignableMerchantsFromCircle call, so a test can check which amounts
    // the eligibility read actually carried rather than only what it answered.
    private val eligibilityCalldataLog = mutableListOf<String>()
    private var failEligibilityCall = false

    private val rpcEngine =
        MockEngine { request ->
            val bytes = (request.body as io.ktor.http.content.OutgoingContent.ByteArrayContent).bytes()
            val payload = Json.parseToJsonElement(bytes.decodeToString()) as JsonObject
            val method = payload["method"]!!.jsonPrimitive.content
            rpcRequestLog += method
            val body =
                when (method) {
                    "eth_getTransactionCount" -> {
                        """{"jsonrpc":"2.0","id":1,"result":"0x01"}"""
                    }

                    "eth_maxPriorityFeePerGas" -> {
                        """{"jsonrpc":"2.0","id":1,"result":"0x3b9aca00"}"""
                    }

                    "eth_getBlockByNumber" -> {
                        """{"jsonrpc":"2.0","id":1,"result":{"number":"0x100","timestamp":"0x68000000","baseFeePerGas":"0x77359400"}}"""
                    }

                    "eth_estimateGas" -> {
                        """{"jsonrpc":"2.0","id":1,"result":"0x5208"}"""
                    }

                    "eth_sendRawTransaction" -> {
                        val raw = payload["params"]!!.toString().substringAfter('"').substringBefore('"')
                        rawTxLog += raw
                        val hash = "0x${keccak256(raw.hexToBytes()).toHex()}"
                        rawTxHashes += hash
                        """{"jsonrpc":"2.0","id":1,"result":"$hash"}"""
                    }

                    "eth_getTransactionReceipt" -> {
                        receiptFor(payload)
                    }

                    "eth_call" -> {
                        ethCallResponse(payload)
                    }

                    else -> {
                        error("Unexpected RPC method: $method")
                    }
                }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }

    private var nextUsdcBalance = ENCODED_ZERO
    private var nextFixedFee = ENCODED_SMALL_ORDER_FIXED_FEE_PAY
    private var nextIsOrderExpired = ENCODED_ZERO
    private var placeOrderReceiptSuccess = true
    private var failPlaceOrderReceiptRead = false
    private var nextSubgraphResponse = SUBGRAPH_OK_ONE_CIRCLE
    private val subgraphEngine =
        MockEngine {
            respond(nextSubgraphResponse, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }

    private val rpcHttp = HttpClient(rpcEngine) { install(ContentNegotiation) { json() } }
    private val subgraphHttp = HttpClient(subgraphEngine) { install(ContentNegotiation) { json() } }
    private val rpc = BaseRpcClient(rpcHttp, "http://mock/rpc")
    private val signer = EoaSigner(rpc, chainId = network.chainId, account = account)
    private val subgraph = SubgraphClient(subgraphHttp, "http://mock/graph")
    private val orderReader = ScriptedOrderReadSource()

    // On-chain pubkey verifier (H1). Defaults to a snapshot whose pubkey matches the accepted
    // (subgraph) snapshot, so happy-path tests pass verification; override `next` to simulate a
    // tampered subgraph pubkey.
    private val onChainVerifier =
        object : OrderReadSource {
            var next: OrderSnapshot? =
                snapshot(
                    status = OrderStatus.ACCEPTED,
                    pubkey = MERCHANT_PUBKEY,
                    merchant = MERCHANT_ADDRESS,
                )

            override suspend fun fetchOrder(orderId: BigInteger): OrderSnapshot? = next
        }

    private val orchestrator =
        OfframpOrchestrator(
            rpc = rpc,
            submitter = signer,
            accountAddress = account.address,
            network = network,
            subgraph = subgraph,
            orderReader = orderReader,
            funding = OfframpFunding { _, _, _, _ -> FundingOutcome.AlreadyFunded(Usdc6.ZERO) },
            refund = refundTo(null),
            router = CircleRouter(random = Random(0), epsilon = 0.0),
            pollIntervalMs = 0,
            stalledAfterMs = 50,
            clockMs = ::nextTick,
            onChainOrderReader = onChainVerifier,
        )

    // Monotonic per-call counter so tests don't depend on wall-clock advancing under runTest's
    // virtual scheduler. With stalledAfterMs=50 and a per-call increment of 20, the third
    // observation flips stalled→true regardless of how the test runtime schedules suspensions.
    private var tickCounter = 0L

    private fun nextTick(): Long {
        tickCounter += 20
        return tickCounter
    }

    @BeforeTest
    fun resetMockState() {
        // kotlin.test on JVM uses per-test instantiation, so these instance fields reset
        // implicitly. This explicit reset is belt-and-braces against the day someone promotes
        // any of them to `companion object` / `@JvmStatic` — at which point silent cross-test
        // contamination would otherwise depend on JUnit's reflection ordering.
        nextUsdcBalance = ENCODED_ZERO
        nextFixedFee = ENCODED_SMALL_ORDER_FIXED_FEE_PAY
        nextIsOrderExpired = ENCODED_ZERO
        placeOrderReceiptSuccess = true
        failPlaceOrderReceiptRead = false
        nextSubgraphResponse = SUBGRAPH_OK_ONE_CIRCLE
        getAssignableResponse = ENCODED_ADDRESS_ARRAY_OF_ONE
        failEligibilityCall = false
        eligibilityCalldataLog.clear()
        rpcRequestLog.clear()
        rawTxLog.clear()
        rawTxHashes.clear()
        tickCounter = 0L
    }

    @AfterTest
    fun shutdown() {
        rpcHttp.close()
        subgraphHttp.close()
    }

    @Test
    fun `happy path emits the full status sequence and ends in Completed`() =
        runTest {
            nextUsdcBalance = ENCODED_FIVE_USDC
            orderReader.enqueue(
                snapshot(status = OrderStatus.ACCEPTED, pubkey = MERCHANT_PUBKEY, merchant = MERCHANT_ADDRESS),
                snapshot(
                    status = OrderStatus.COMPLETED,
                    pubkey = MERCHANT_PUBKEY,
                    merchant = MERCHANT_ADDRESS,
                    actualFiatAmount = Usdc6.ofMicros(445_000_000),
                    actualUsdcAmount = Usdc6.ofMicros(5_062_500),
                    completedAtEpochSeconds = 1_779_999_999L,
                ),
            )

            val statuses = orchestrator.run(payRequest()).toList()

            assertIs<OfframpStatus.Idle>(statuses.first())
            val completed = statuses.last() as OfframpStatus.Completed
            assertEquals(ORDER_ID, completed.orderId)
            assertEquals(1_779_999_999L, completed.completedAtEpochSeconds)

            val classes = statuses.map { it::class.simpleName }
            assertTrue(classes.indexOf("Idle") < classes.indexOf("SelectingCircle"))
            assertTrue(classes.indexOf("SelectingCircle") < classes.indexOf("ApprovingUsdc"))
            assertTrue(classes.indexOf("ApprovingUsdc") < classes.indexOf("PlacingOrder"))
            assertTrue(classes.indexOf("PlacingOrder") < classes.indexOf("WaitingForMerchantAcceptance"))
            assertTrue(classes.indexOf("WaitingForMerchantAcceptance") < classes.indexOf("SendingEncryptedUpi"))
            assertTrue(classes.indexOf("SendingEncryptedUpi") < classes.indexOf("WaitingForCompletion"))
            assertTrue(classes.indexOf("WaitingForCompletion") < classes.indexOf("Completed"))

            // 4 broadcasts: approve, placeOrder, exact fee re-approve, setSellOrderUpi.
            assertEquals(4, rawTxLog.size, "expected 4 broadcasts, got ${rawTxLog.size}")
        }

    @Test
    fun `small PAY orders approve amount plus configured fixed fee`() =
        runTest {
            orderReader.enqueue(
                snapshot(status = OrderStatus.ACCEPTED, pubkey = MERCHANT_PUBKEY, merchant = MERCHANT_ADDRESS),
                snapshot(status = OrderStatus.COMPLETED, pubkey = MERCHANT_PUBKEY, merchant = MERCHANT_ADDRESS),
            )

            val statuses = orchestrator.run(payRequest()).toList()

            val approving = statuses.filterIsInstance<OfframpStatus.ApprovingUsdc>().single()
            assertEquals(Usdc6.ofMicros(5_100_000), approving.amount)
        }

    @Test
    fun `fee increase after an authorized quote fails before the first broadcast`() =
        runTest {
            nextFixedFee = ENCODED_TWO_TENTHS_USDC

            val statuses =
                orchestrator
                    .run(
                        payRequest(
                            authorizedPayFee = Usdc6.ofMicros(100_000),
                            authorizedRequiredBalance = Usdc6.ofMicros(5_100_000),
                        ),
                    ).toList()

            val failure = assertIs<OfframpStatus.Failed>(statuses.last())
            assertEquals(OfframpStep.INITIALIZATION, failure.step)
            assertTrue(failure.message.contains("fee changed"))
            assertEquals(0, rawTxLog.size)
        }

    @Test
    fun `place marker persistence failure aborts before the placeOrder send`() =
        runTest {
            val failure =
                assertFailsWith<PlaceOrderMarkerPersistenceException> {
                    orchestrator.run(payRequest()).collect { status ->
                        if (status is OfframpStatus.PlacingOrder) error("disk full")
                    }
                }

            assertTrue(failure.cause?.message?.contains("disk full") == true)
            assertEquals(1, rawTxLog.size, "only the already-confirmed approval may have broadcast")
        }

    @Test
    fun `reverted exact placeOrder receipt retires the unresolved marker`() =
        runTest {
            placeOrderReceiptSuccess = false

            val statuses = orchestrator.resume(unresolvedPlaceCheckpoint()).toList()

            val failure = assertIs<OfframpStatus.Failed>(statuses.last())
            assertEquals(OfframpStep.PLACING_ORDER, failure.step)
            assertTrue(failure.nothingEscrowed)
            assertEquals(0, rawTxLog.size)
        }

    @Test
    fun `unreadable exact placeOrder receipt preserves the unresolved marker`() =
        runTest {
            failPlaceOrderReceiptRead = true

            val statuses = orchestrator.resume(unresolvedPlaceCheckpoint()).toList()

            val failure = assertIs<OfframpStatus.Failed>(statuses.last())
            assertEquals(OfframpStep.PLACING_ORDER, failure.step)
            assertFalse(failure.nothingEscrowed)
            assertEquals(0, rawTxLog.size)
        }

    @Test
    fun `PAY orders above the small-order threshold do not approve the fixed fee`() =
        runTest {
            orderReader.enqueue(
                snapshot(status = OrderStatus.ACCEPTED, pubkey = MERCHANT_PUBKEY, merchant = MERCHANT_ADDRESS),
                snapshot(status = OrderStatus.COMPLETED, pubkey = MERCHANT_PUBKEY, merchant = MERCHANT_ADDRESS),
            )

            val statuses =
                orchestrator
                    .run(
                        payRequest(
                            usdcAmount = Usdc6.ofMicros(20_000_000),
                            fiatAmount = Usdc6.ofMicros(1_780_000_000),
                        ),
                    ).toList()

            val approving = statuses.filterIsInstance<OfframpStatus.ApprovingUsdc>().single()
            assertEquals(Usdc6.ofMicros(20_000_000), approving.amount)
        }

    @Test
    fun `subgraph returning no circles surfaces as Failed with no orderId and SELECTING_CIRCLE step`() =
        runTest {
            nextSubgraphResponse = """{"data":{"circles":[]}}"""

            val statuses = orchestrator.run(payRequest()).toList()
            val last = assertIs<OfframpStatus.Failed>(statuses.last())
            assertEquals(null, last.orderId)
            assertEquals(OfframpStep.SELECTING_CIRCLE, last.step)
        }

    @Test
    fun `orchestrator absorbs OrderReadSource exceptions during polling and continues`() =
        runTest {
            nextUsdcBalance = ENCODED_FIVE_USDC
            // Regression for: a single bad poll mid-flight used to throw out of orderReader.fetchOrder
            // and bail the orchestrator into Failed, orphaning escrowed USDC. The orchestrator now
            // wraps each fetchOrder call in a runCatching so a thrown reader collapses to "no
            // observation this tick" and polling continues. Fallback-between-sources behaviour
            // (subgraph fails → on-chain wins) is covered in FallbackOrderReaderTest; this test
            // exercises only the orchestrator's catch-around-reader.
            orderReader.enqueue(null) // reader returns null (no observation this tick)
            orderReader.enqueueThrow(RuntimeException("kapow")) // even an outright throw is absorbed
            orderReader.enqueue(snapshot(status = OrderStatus.ACCEPTED, pubkey = MERCHANT_PUBKEY, merchant = MERCHANT_ADDRESS))
            orderReader.enqueue(
                snapshot(status = OrderStatus.COMPLETED, pubkey = MERCHANT_PUBKEY, merchant = MERCHANT_ADDRESS),
            )

            val statuses = orchestrator.run(payRequest()).toList()
            assertIs<OfframpStatus.Completed>(statuses.last())
        }

    @Test
    fun `WaitingForMerchantAcceptance flips stalled=true after stalledAfterMs of polling`() =
        runTest {
            // No accepted snapshot enqueued for a while → orchestrator loops returning null. Once we've
            // been polling longer than stalledAfterMs (50ms in tests, default 5min), the emitted
            // WaitingForMerchantAcceptance should carry stalled=true. We then drop ACCEPTED + COMPLETED
            // in to terminate the flow cleanly.
            repeat(5) { orderReader.enqueue(null) }
            orderReader.enqueue(snapshot(status = OrderStatus.ACCEPTED, pubkey = MERCHANT_PUBKEY, merchant = MERCHANT_ADDRESS))
            orderReader.enqueue(
                snapshot(status = OrderStatus.COMPLETED, pubkey = MERCHANT_PUBKEY, merchant = MERCHANT_ADDRESS),
            )

            val statuses = orchestrator.run(payRequest()).toList()
            val anyStalledAcceptance =
                statuses
                    .filterIsInstance<OfframpStatus.WaitingForMerchantAcceptance>()
                    .any { it.stalled }
            assertTrue(anyStalledAcceptance, "expected at least one WaitingForMerchantAcceptance.stalled=true emission")
        }

    @Test
    fun `WaitingForMerchantAcceptance carries expired=true when Diamond reports the order as expired`() =
        runTest {
            nextUsdcBalance = ENCODED_FIVE_USDC
            nextIsOrderExpired = ENCODED_ONE
            orderReader.enqueue(null) // one extra null poll so a WaitingFor* emission with expired=true is observed
            orderReader.enqueue(snapshot(status = OrderStatus.PLACED, pubkey = "", merchant = null))
            orderReader.enqueue(snapshot(status = OrderStatus.ACCEPTED, pubkey = MERCHANT_PUBKEY, merchant = MERCHANT_ADDRESS))
            orderReader.enqueue(snapshot(status = OrderStatus.COMPLETED, pubkey = MERCHANT_PUBKEY, merchant = MERCHANT_ADDRESS))

            val statuses = orchestrator.run(payRequest()).toList()

            assertTrue(
                statuses.filterIsInstance<OfframpStatus.WaitingForMerchantAcceptance>().any { it.expired },
                "expected at least one WaitingForMerchantAcceptance.expired=true when Diamond.isOrderExpired returns 1",
            )
            assertIs<OfframpStatus.Completed>(statuses.last())
        }

    @Test
    fun `cancelled order during acceptance polling emits Cancelled terminal not Failed`() =
        runTest {
            orderReader.enqueue(
                snapshot(status = OrderStatus.CANCELLED, pubkey = "", merchant = null),
            )

            val statuses = orchestrator.run(payRequest()).toList()
            val last = assertIs<OfframpStatus.Cancelled>(statuses.last())
            assertEquals(ORDER_ID, last.orderId)
            assertEquals(1_779_500_000L, last.cancelledAtEpochSeconds)
            // Refunded amount falls back to the placed usdcAmount when subgraph's actualUsdcAmount
            // is null (it only populates on COMPLETED).
            assertEquals(Usdc6.ofMicros(5_000_000), last.refundedUsdcAmount)
        }

    @Test
    fun `resume does not re-send setSellOrderUpi when the order has advanced past ACCEPTED`() =
        runTest {
            // Process died after the setSellOrderUpi tx landed but before its hash was checkpointed, so
            // the checkpoint has no setUpiTxHash. On resume the order is already PAID; re-sending would
            // revert with UpiAlreadySent. The orchestrator must skip straight to completion polling.
            orderReader.enqueue(snapshot(status = OrderStatus.PAID, pubkey = MERCHANT_PUBKEY, merchant = MERCHANT_ADDRESS))
            orderReader.enqueue(snapshot(status = OrderStatus.COMPLETED, pubkey = MERCHANT_PUBKEY, merchant = MERCHANT_ADDRESS))

            val statuses = orchestrator.resume(resumeCheckpoint(setUpiTxHash = null)).toList()

            assertIs<OfframpStatus.Completed>(statuses.last())
            assertEquals(0, rawTxLog.size, "resume must not broadcast setSellOrderUpi when UPI is already on-chain")
            assertTrue(
                statuses.none { it is OfframpStatus.SendingEncryptedUpi },
                "no SendingEncryptedUpi should be emitted when the UPI tx is skipped",
            )
        }

    @Test
    fun `resume does not re-send setSellOrderUpi when encryptedUserUpi is already populated`() =
        runTest {
            // Same race, detected via the on-chain field rather than the status: the order is still
            // ACCEPTED but encUpi is already set, so the tx landed and we must not re-broadcast.
            orderReader.enqueue(
                snapshot(
                    status = OrderStatus.ACCEPTED,
                    pubkey = MERCHANT_PUBKEY,
                    merchant = MERCHANT_ADDRESS,
                    encryptedUserUpi = "0xdeadbeef",
                ),
            )
            orderReader.enqueue(snapshot(status = OrderStatus.COMPLETED, pubkey = MERCHANT_PUBKEY, merchant = MERCHANT_ADDRESS))

            val statuses = orchestrator.resume(resumeCheckpoint(setUpiTxHash = null)).toList()

            assertIs<OfframpStatus.Completed>(statuses.last())
            assertEquals(0, rawTxLog.size, "resume must not broadcast setSellOrderUpi when encUpi is already on-chain")
        }

    // -- Funding: idempotency, route re-validation, recovery (mainnet bridge seam) -------------

    @Test
    fun `resume of a pre order bridge re polls the persisted handle and never re quotes`() =
        runTest {
            nextUsdcBalance = ENCODED_FIVE_USDC
            // Crash after the ZEC deposit but before the order was placed. The checkpoint carries the
            // 1-Click depositAddress and no orderId. Resume MUST hand that handle back to funding (so it
            // re-polls the in-flight bridge) instead of opening a second bridge — the double-send fix.
            val seenResumeHandles = mutableListOf<String?>()
            val orchestrator =
                orchestratorWith(
                    funding =
                        OfframpFunding { _, _, resumeHandle, _ ->
                            seenResumeHandles += resumeHandle
                            FundingOutcome.Bridged(depositAddress = resumeHandle ?: "near-deposit-abc")
                        },
                )
            orderReader.enqueue(snapshot(status = OrderStatus.ACCEPTED, pubkey = MERCHANT_PUBKEY, merchant = MERCHANT_ADDRESS))
            orderReader.enqueue(snapshot(status = OrderStatus.COMPLETED, pubkey = MERCHANT_PUBKEY, merchant = MERCHANT_ADDRESS))

            val statuses = orchestrator.resume(bridgeResumeCheckpoint(depositAddress = "near-deposit-abc")).toList()

            assertEquals(listOf<String?>("near-deposit-abc"), seenResumeHandles, "resume must pass the persisted handle")
            assertIs<OfframpStatus.Completed>(statuses.last())
        }

    @Test
    fun `onBridgeStarted emits BridgingFunds with the deposit address before approving`() =
        runTest {
            val orchestrator =
                orchestratorWith(
                    funding =
                        OfframpFunding { _, _, _, onBridgeStarted ->
                            onBridgeStarted("near-deposit-xyz")
                            FundingOutcome.Bridged(depositAddress = "near-deposit-xyz")
                        },
                )
            orderReader.enqueue(snapshot(status = OrderStatus.ACCEPTED, pubkey = MERCHANT_PUBKEY, merchant = MERCHANT_ADDRESS))
            orderReader.enqueue(snapshot(status = OrderStatus.COMPLETED, pubkey = MERCHANT_PUBKEY, merchant = MERCHANT_ADDRESS))

            val statuses = orchestrator.run(payRequest()).toList()

            val bridging = statuses.filterIsInstance<OfframpStatus.BridgingFunds>().single()
            assertEquals("near-deposit-xyz", bridging.depositAddress)
            val classes = statuses.map { it::class.simpleName }
            assertTrue(
                classes.indexOf("BridgingFunds") < classes.indexOf("ApprovingUsdc"),
                "the deposit address must be surfaced (and persisted) before any USDC tx",
            )
        }

    @Test
    fun `route re-validation fails closed when the merchant vanishes during funding`() =
        runTest {
            // Eligibility passes at circle-selection time, then the (multi-minute) bridge runs and the
            // merchant drops out. The post-funding re-check must fail BEFORE approve, so the bridged USDC
            // is never committed to a placeOrder that would revert.
            val orchestrator =
                orchestratorWith(
                    funding =
                        OfframpFunding { _, _, _, _ ->
                            getAssignableResponse = ENCODED_EMPTY_ADDRESS_ARRAY
                            FundingOutcome.AlreadyFunded(Usdc6.ZERO)
                        },
                )

            val statuses = orchestrator.run(payRequest()).toList()

            val failed = assertIs<OfframpStatus.Failed>(statuses.last())
            assertEquals(OfframpStep.FUNDING, failed.step)
            assertEquals(0, rawTxLog.size, "no approve/placeOrder may broadcast once the route is gone")
        }

    @Test
    fun `bridgeFundsBackToZec routes balance to the pull back target without an order`() =
        runTest {
            nextUsdcBalance = ENCODED_FIVE_USDC
            val pullback = Address.parse("0x2222222222222222222222222222222222222222")
            var awaitedHandle: String? = null
            val orchestrator =
                orchestratorWith(
                    funding = OfframpFunding { _, _, _, _ -> FundingOutcome.AlreadyFunded(Usdc6.ZERO) },
                    refund = refundTo(pullback) { awaitedHandle = it },
                )

            val statuses = orchestrator.bridgeFundsBackToZec(orderId = null).toList()

            val recovered = assertIs<OfframpStatus.FundsRecovered>(statuses.last())
            assertEquals(Usdc6.ofMicros(5_000_000), recovered.amount)
            assertEquals(pullback, recovered.target)
            assertEquals(1, rawTxLog.size, "one USDC.transfer to the pull-back target")
            assertEquals(pullback.checksumHex, awaitedHandle, "success waits for the same bridge handle")
        }

    @Test
    fun `resumed refund never transfers a later Base balance to the saved handle`() =
        runTest {
            // These funds may have arrived after the original refund transfer. Balance inference
            // would debit them a second time; the persisted transfer phase must win instead.
            nextUsdcBalance = ENCODED_FIVE_USDC
            val pullback = Address.parse("0x2222222222222222222222222222222222222222")
            var awaitedHandle: String? = null
            val orchestrator =
                orchestratorWith(
                    funding = OfframpFunding { _, _, _, _ -> FundingOutcome.AlreadyFunded(Usdc6.ZERO) },
                    refund = refundTo(pullback) { awaitedHandle = it },
                )

            val statuses =
                orchestrator
                    .bridgeFundsBackToZec(
                        orderId = null,
                        resume =
                            RefundResume(
                                pullback.checksumHex,
                                Usdc6.ofMicros(5_000_000),
                                transferStarted = true,
                            ),
                    ).toList()

            assertIs<OfframpStatus.FundsRecovered>(statuses.last())
            assertEquals(0, rawTxLog.size, "already-funded bridge must not transfer USDC twice")
            assertEquals(pullback.checksumHex, awaitedHandle)
        }

    @Test
    fun `bridgeFundsBackToZec with no route leaves the USDC in the testnet account`() =
        runTest {
            nextUsdcBalance = ENCODED_FIVE_USDC
            val orchestrator =
                orchestratorWith(
                    funding = OfframpFunding { _, _, _, _ -> FundingOutcome.AlreadyFunded(Usdc6.ZERO) },
                    refund = refundTo(null),
                )

            val statuses = orchestrator.bridgeFundsBackToZec(orderId = null).toList()

            val recovered = assertIs<OfframpStatus.FundsRecovered>(statuses.last())
            assertEquals(Usdc6.ofMicros(5_000_000), recovered.amount)
            assertEquals(null, recovered.target)
            assertEquals(0, rawTxLog.size, "no route → no transfer; self-custodial balance stays put")
        }

    @Test
    fun `setSellOrderUpi fails closed when subgraph merchant pubkey disagrees with chain`() =
        runTest {
            // A compromised subgraph hands an attacker pubkey for the accepted order. The orchestrator must
            // re-read the pubkey on-chain, detect the mismatch, and refuse to encrypt the UPI to it — so the
            // user's plaintext UPI handle never leaks to an attacker key.
            onChainVerifier.next = snapshot(status = OrderStatus.ACCEPTED, pubkey = MERCHANT_PUBKEY, merchant = MERCHANT_ADDRESS)
            orderReader.enqueue(snapshot(status = OrderStatus.ACCEPTED, pubkey = ATTACKER_PUBKEY, merchant = MERCHANT_ADDRESS))

            val statuses = orchestrator.run(payRequest()).toList()

            val failed = assertIs<OfframpStatus.Failed>(statuses.last())
            assertEquals(OfframpStep.ENCRYPTING_UPI, failed.step)
            // approve + placeOrder broadcast (2); setSellOrderUpi must NOT — the UPI is never encrypted.
            assertEquals(2, rawTxLog.size, "setSellOrderUpi must not broadcast on a pubkey mismatch")
            assertTrue(statuses.none { it is OfframpStatus.SendingEncryptedUpi })
        }

    @Test
    fun `bridgeToBase bridges then completes with the post-bridge Base balance`() =
        runTest {
            nextUsdcBalance = ENCODED_FIVE_USDC // balance read after the bridge settles
            val orchestrator =
                orchestratorWith(
                    funding = OfframpFunding { _, _, _, _ -> FundingOutcome.AlreadyFunded(Usdc6.ZERO) },
                    topUp =
                        OfframpTopUp { _, _, _, onBridgeStarted ->
                            onBridgeStarted("near-deposit-xyz")
                            FundingOutcome.Bridged(depositAddress = "near-deposit-xyz")
                        },
                )

            val statuses =
                orchestrator
                    .bridgeToBase(Usdc6.ofMicros(2_000_000), resumeBridgeHandle = null)
                    .toList()

            assertIs<BridgeToBaseStatus.Idle>(statuses.first())
            val bridging = statuses.filterIsInstance<BridgeToBaseStatus.Bridging>().single()
            assertEquals("near-deposit-xyz", bridging.depositAddress)
            val complete = assertIs<BridgeToBaseStatus.Complete>(statuses.last())
            assertEquals(Usdc6.ofMicros(2_000_000), complete.addedAmount)
            assertEquals(Usdc6.ofMicros(5_000_000), complete.baseBalance)
        }

    @Test
    fun `bridgeToBase resume forwards the persisted handle so the top-up re-polls instead of re-quoting`() =
        runTest {
            // The double-send fix for the standalone top-up: a non-null resumeBridgeHandle MUST be handed
            // to topUp.bridge so it re-polls the already-opened 1-Click deposit, never opening a second
            // bridge and re-sending the user's ZEC.
            nextUsdcBalance = ENCODED_FIVE_USDC
            val seenResumeHandles = mutableListOf<String?>()
            val orchestrator =
                orchestratorWith(
                    funding = OfframpFunding { _, _, _, _ -> FundingOutcome.AlreadyFunded(Usdc6.ZERO) },
                    topUp =
                        OfframpTopUp { _, _, resumeHandle, onBridgeStarted ->
                            seenResumeHandles += resumeHandle
                            resumeHandle?.let { onBridgeStarted(it) }
                            FundingOutcome.Bridged(depositAddress = resumeHandle ?: "near-deposit-fresh")
                        },
                )

            val statuses =
                orchestrator
                    .bridgeToBase(Usdc6.ofMicros(2_000_000), resumeBridgeHandle = "near-deposit-abc")
                    .toList()

            assertEquals(
                listOf<String?>("near-deposit-abc"),
                seenResumeHandles,
                "resume must forward the persisted handle (re-poll, not re-quote)",
            )
            val bridging = statuses.filterIsInstance<BridgeToBaseStatus.Bridging>().single()
            assertEquals("near-deposit-abc", bridging.depositAddress)
            assertIs<BridgeToBaseStatus.Complete>(statuses.last())
        }

    @Test
    fun `bridgeToBase surfaces Failed carrying the deposit address when the bridge throws`() =
        runTest {
            val orchestrator =
                orchestratorWith(
                    funding = OfframpFunding { _, _, _, _ -> FundingOutcome.AlreadyFunded(Usdc6.ZERO) },
                    topUp =
                        OfframpTopUp { _, _, _, onBridgeStarted ->
                            onBridgeStarted("near-deposit-zzz")
                            error("bridge blew up")
                        },
                )

            val statuses =
                orchestrator
                    .bridgeToBase(Usdc6.ofMicros(2_000_000), resumeBridgeHandle = null)
                    .toList()

            val failed = assertIs<BridgeToBaseStatus.Failed>(statuses.last())
            assertEquals("near-deposit-zzz", failed.depositAddress)
        }

    @Test
    fun `merchantAvailability is Available with an assignable merchant and Unavailable without`() =
        runTest {
            getAssignableResponse = ENCODED_ADDRESS_ARRAY_OF_ONE
            assertEquals(
                MerchantAvailability.Available,
                orchestrator.merchantAvailability(
                    usdc = Usdc6.ofMicros(5_000_000),
                    fiat = Usdc6.ofMicros(445_000_000),
                    currency = CurrencyCode.Inr,
                ),
            )

            getAssignableResponse = ENCODED_EMPTY_ADDRESS_ARRAY
            assertEquals(
                MerchantAvailability.Unavailable,
                orchestrator.merchantAvailability(
                    usdc = Usdc6.ofMicros(5_000_000),
                    fiat = Usdc6.ofMicros(445_000_000),
                    currency = CurrencyCode.Inr,
                ),
            )
        }

    // The bug this guards: probing with a placeholder fiat amount asks the Diamond a different
    // question than the order asks, and gets a near-uniform yes back.
    @Test
    fun `merchantAvailability asks the chain about the fiat amount it was given`() =
        runTest {
            orchestrator.merchantAvailability(
                usdc = Usdc6.ofMicros(5_000_000),
                fiat = Usdc6.ofMicros(445_000_000),
                currency = CurrencyCode.Inr,
            )

            assertEquals(bigIntegerValueOf(445_000_000), fiatAmountOf(eligibilityCalldataLog.single()))
        }

    @Test
    fun `merchantAvailability reports Undetermined rather than Unavailable when the read fails`() =
        runTest {
            failEligibilityCall = true

            val availability =
                orchestrator.merchantAvailability(
                    usdc = Usdc6.ofMicros(5_000_000),
                    fiat = Usdc6.ofMicros(445_000_000),
                    currency = CurrencyCode.Inr,
                )

            assertIs<MerchantAvailability.Undetermined>(availability)
        }

    @Test
    fun `merchantAvailability reports Undetermined when the subgraph cannot be read`() =
        runTest {
            nextSubgraphResponse = "not json at all"

            val availability =
                orchestrator.merchantAvailability(
                    usdc = Usdc6.ofMicros(5_000_000),
                    fiat = Usdc6.ofMicros(445_000_000),
                    currency = CurrencyCode.Inr,
                )

            assertIs<MerchantAvailability.Undetermined>(availability)
        }

    @Test
    fun `merchantAvailability is Unavailable when the corridor has no circle to try`() =
        runTest {
            nextSubgraphResponse = """{"data":{"circles":[]}}"""

            assertEquals(
                MerchantAvailability.Unavailable,
                orchestrator.merchantAvailability(
                    usdc = Usdc6.ofMicros(5_000_000),
                    fiat = Usdc6.ofMicros(445_000_000),
                    currency = CurrencyCode.Inr,
                ),
            )
        }

    /**
     * `fiatAmount` is the sixth argument of `getAssignableMerchantsFromCircle(circleId, assignUpto,
     * currency, user, usdtAmount, fiatAmount, orderType, preferredPCConfigId)`, so word 5 of the
     * argument block that follows the 4-byte selector.
     */
    private fun fiatAmountOf(callParams: String): BigInteger {
        val args = callParams.substringAfter("0x36b0ec9a").substring(0, ABI_WORD_HEX * 8)
        val word = args.substring(ABI_WORD_HEX * 5, ABI_WORD_HEX * 6)
        return BigInteger(word, 16)
    }

    private fun payRequest(
        usdcAmount: Usdc6 = Usdc6.ofMicros(5_000_000),
        fiatAmount: Usdc6 = Usdc6.ofMicros(445_000_000),
        fiatAmountLimit: Usdc6? = Usdc6.ofMicros(445_000_000),
        authorizedPayFee: Usdc6? = null,
        authorizedRequiredBalance: Usdc6? = null,
    ) =
        OfframpRequest(
            recipientUpi = "merchant@upi",
            usdcAmount = usdcAmount,
            fiatAmount = fiatAmount,
            fiatAmountLimit = fiatAmountLimit,
            currency = CurrencyCode.Inr,
            authorizedPayFee = authorizedPayFee,
            authorizedRequiredBalance = authorizedRequiredBalance,
        )

    private fun orchestratorWith(
        funding: OfframpFunding,
        refund: OfframpRefund = refundTo(null),
        topUp: OfframpTopUp = OfframpTopUp { _, _, _, _ -> error("no top-up configured") },
    ) = OfframpOrchestrator(
        rpc = rpc,
        submitter = signer,
        accountAddress = account.address,
        network = network,
        subgraph = subgraph,
        orderReader = orderReader,
        funding = funding,
        refund = refund,
        topUp = topUp,
        router = CircleRouter(random = Random(0), epsilon = 0.0),
        pollIntervalMs = 0,
        stalledAfterMs = 50,
        clockMs = ::nextTick,
        onChainOrderReader = onChainVerifier,
    )

    private fun refundTo(target: Address?, onAwait: suspend (String) -> Unit = {}): OfframpRefund =
        object : OfframpRefund {
            override suspend fun pullbackTarget(account: Address, amount: Usdc6): Address? = target

            override suspend fun awaitSettlement(handle: String) = onAwait(handle)
        }

    private fun bridgeResumeCheckpoint(depositAddress: String?) =
        OfframpCheckpoint(
            orderId = null,
            currentStep = OfframpStep.FUNDING,
            bridgeDepositAddress = depositAddress,
            recipientUpi = "merchant@upi",
            usdcAmountMicroDecimal = "5000000",
            fiatAmountMicroDecimal = "445000000",
            fiatAmountLimitMicroDecimal = "445000000",
            currency = CurrencyCode.Inr,
            createdAtMillis = 0,
        )

    private fun unresolvedPlaceCheckpoint() =
        OfframpCheckpoint(
            orderId = null,
            currentStep = OfframpStep.PLACING_ORDER,
            placeOrderTxHash =
                xyz.justzappit.evm.types.TxHash
                    .fromHex(PLACE_ORDER_TX_HASH),
            recipientUpi = "merchant@upi",
            usdcAmountMicroDecimal = "5000000",
            fiatAmountMicroDecimal = "445000000",
            fiatAmountLimitMicroDecimal = "445000000",
            currency = CurrencyCode.Inr,
            createdAtMillis = 0,
        )

    private fun resumeCheckpoint(setUpiTxHash: xyz.justzappit.evm.types.TxHash?) =
        OfframpCheckpoint(
            orderId = ORDER_ID.toString(),
            currentStep = OfframpStep.WAITING_FOR_ACCEPTANCE,
            placeOrderTxHash =
                xyz.justzappit.evm.types.TxHash
                    .fromHex("0x" + "02".padStart(64, '0')),
            setUpiTxHash = setUpiTxHash,
            recipientUpi = "merchant@upi",
            usdcAmountMicroDecimal = "5000000",
            fiatAmountMicroDecimal = "445000000",
            fiatAmountLimitMicroDecimal = "445000000",
            currency = CurrencyCode.Inr,
            createdAtMillis = 0,
        )

    private fun snapshot(
        status: OrderStatus,
        pubkey: String,
        merchant: String?,
        actualUsdcAmount: Usdc6? = null,
        actualFiatAmount: Usdc6? = null,
        completedAtEpochSeconds: Long? = null,
        encryptedUserUpi: String = "",
    ) = OrderSnapshot(
        orderId = ORDER_ID,
        status = status,
        orderType = OrderType.PAY,
        circleId = bigIntegerOne,
        userAddress = account.address,
        usdcAmount = Usdc6.ofMicros(5_000_000),
        fiatAmount = Usdc6.ofMicros(445_000_000),
        currencyHex = "0x494e520000000000000000000000000000000000000000000000000000000000",
        acceptedMerchantAddress = merchant?.let { Address.parse(it) },
        merchantPubKey = pubkey,
        encryptedUserUpi = encryptedUserUpi,
        encryptedMerchantUpi = "",
        placedAtEpochSeconds = 1_779_000_000L,
        acceptedAtEpochSeconds = if (status.onChain >= OrderStatus.ACCEPTED.onChain) 1_779_500_000L else null,
        paidAtEpochSeconds = null,
        completedAtEpochSeconds = completedAtEpochSeconds,
        cancelledAtEpochSeconds = if (status == OrderStatus.CANCELLED) 1_779_500_000L else null,
        actualUsdcAmount = actualUsdcAmount,
        actualFiatAmount = actualFiatAmount,
        placedTxHash =
            xyz.justzappit.evm.types.TxHash.fromHex(
                "0x" + "02".padStart(64, '0'),
            ),
        source = OrderSnapshot.Source.Subgraph,
    )

    private class ScriptedOrderReadSource : OrderReadSource {
        // Items are either an OrderSnapshot, null, or a throwable to raise. ArrayDeque<Any?> with
        // throwable sentinel keeps the test ergonomic without a separate field.
        private val queue = ArrayDeque<Any?>()

        fun enqueue(vararg snapshots: OrderSnapshot?) {
            snapshots.forEach { queue.addLast(it) }
        }

        fun enqueueThrow(error: Throwable) {
            queue.addLast(ThrowSentinel(error))
        }

        override suspend fun fetchOrder(orderId: BigInteger): OrderSnapshot? {
            if (queue.isEmpty()) return null
            return when (val head = queue.removeFirst()) {
                is ThrowSentinel -> throw head.error
                is OrderSnapshot -> head
                null -> null
                else -> error("unexpected scripted item: $head")
            }
        }

        private class ThrowSentinel(
            val error: Throwable
        )
    }

    private fun receiptFor(payload: JsonObject): String {
        val txParam = payload["params"]!!.toString().substringAfter('"').substringBefore('"')
        return when {
            failPlaceOrderReceiptRead && txParam == PLACE_ORDER_TX_HASH -> {
                """{"jsonrpc":"2.0","id":1,"error":{"code":-32000,"message":"receipt unavailable"}}"""
            }

            txParam == PLACE_ORDER_TX_HASH || txParam == rawTxHashes.getOrNull(1) -> {
                placeOrderReceiptJson(txParam, success = placeOrderReceiptSuccess)
            }

            else -> {
                simpleSuccessReceiptJson(txParam)
            }
        }
    }

    private fun ethCallResponse(payload: JsonObject): String {
        val params = payload["params"]!!.toString()
        return when {
            params.contains("0x36b0ec9a") -> {
                eligibilityCalldataLog += params
                if (failEligibilityCall) {
                    """{"jsonrpc":"2.0","id":1,"error":{"code":-32000,"message":"execution reverted"}}"""
                } else {
                    """{"jsonrpc":"2.0","id":1,"result":"$getAssignableResponse"}"""
                }
            }

            params.contains("0x70a08231") -> {
                // ERC-20 balanceOf
                """{"jsonrpc":"2.0","id":1,"result":"$nextUsdcBalance"}"""
            }

            params.contains("0x59c69313") -> {
                // isOrderExpired(uint256)
                """{"jsonrpc":"2.0","id":1,"result":"$nextIsOrderExpired"}"""
            }

            params.contains("0x67c84efd") -> {
                // getPriceConfig(bytes32)
                """{"jsonrpc":"2.0","id":1,"result":"$ENCODED_PRICE_CONFIG_INR"}"""
            }

            params.contains("0x6b2d3913") -> {
                // getSmallOrderThreshold(bytes32)
                """{"jsonrpc":"2.0","id":1,"result":"$ENCODED_TEN_USDC"}"""
            }

            params.contains("0x1e277523") -> {
                // getSmallOrderFixedFeePay(bytes32)
                """{"jsonrpc":"2.0","id":1,"result":"$nextFixedFee"}"""
            }

            else -> {
                error("Unexpected eth_call: $params")
            }
        }
    }

    private fun simpleSuccessReceiptJson(txHash: String): String =
        """
        {"jsonrpc":"2.0","id":1,"result":{
          "transactionHash":"$txHash",
          "blockNumber":"0x10",
          "status":"0x1",
          "gasUsed":"0x5208",
          "logs":[]
        }}
        """.trimIndent()

    private fun placeOrderReceiptJson(txHash: String, success: Boolean): String {
        val userTopic = "0x" + "0".repeat(24) + account.address.lowercaseHex.removePrefix("0x")
        val orderIdTopic = "0x" + ORDER_ID.toString(16).padStart(64, '0')
        return """
            {"jsonrpc":"2.0","id":1,"result":{
              "transactionHash":"$txHash",
              "blockNumber":"0x10",
              "status":"${if (success) "0x1" else "0x0"}",
              "gasUsed":"0x5208",
              "logs":[
                {
                  "address":"${network.diamondAddress.lowercaseHex}",
                  "topics":["${OrderEvents.ORDER_PLACED_TOPIC}",
                            "$orderIdTopic",
                            "$userTopic",
                            "0x${"0".repeat(64)}"],
                  "data":"0x",
                  "blockNumber":"0x10",
                  "transactionHash":"$txHash",
                  "logIndex":"0x0"
                }
              ]
            }}
            """.trimIndent()
    }

    companion object {
        const val MNEMONIC =
            "abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon abandon abandon about"
        const val MERCHANT_PUBKEY =
            "1b84c5567b126440995d3ed5aaba0565d71e1834604819ff9c17f5e9d5dd078f" +
                "70beaf8f588b541507fed6a642c5ab42dfdf8120a7f639de5122d47a69a8e8d1"
        const val MERCHANT_ADDRESS = "0x1111111111111111111111111111111111111111"

        // A distinct (attacker-controlled) pubkey for the H1 tamper test — never used for real crypto.
        val ATTACKER_PUBKEY = "00".repeat(64)
        val ORDER_ID: BigInteger = bigIntegerValueOf(7)

        // Synthetic 32-byte hash whose trailing byte (0x02) matches the second mocked broadcast.
        private const val PLACE_ORDER_TX_HASH = "0x" + "0000000000000000000000000000000000000000000000000000000000000002"

        const val ENCODED_ADDRESS_ARRAY_OF_ONE =
            "0x0000000000000000000000000000000000000000000000000000000000000020" +
                "0000000000000000000000000000000000000000000000000000000000000001" +
                "000000000000000000000000111111111111111111111111111111111111baaf"

        // ABI-encoded empty address[] (offset 0x20, length 0): no assignable merchant.
        const val ENCODED_EMPTY_ADDRESS_ARRAY =
            "0x0000000000000000000000000000000000000000000000000000000000000020" +
                "0000000000000000000000000000000000000000000000000000000000000000"

        // One ABI word (32 bytes) as hex characters.
        const val ABI_WORD_HEX = 64

        const val ENCODED_ZERO = "0x" + "0000000000000000000000000000000000000000000000000000000000000000"
        const val ENCODED_ONE = "0x" + "0000000000000000000000000000000000000000000000000000000000000001"
        const val ENCODED_FIVE_USDC = "0x" + "00000000000000000000000000000000000000000000000000000000004c4b40"
        const val ENCODED_TEN_USDC = "0x" + "0000000000000000000000000000000000000000000000000000000000989680"
        const val ENCODED_SMALL_ORDER_FIXED_FEE_PAY =
            "0x" + "00000000000000000000000000000000000000000000000000000000000186a0"
        const val ENCODED_TWO_TENTHS_USDC =
            "0x" + "0000000000000000000000000000000000000000000000000000000000030d40"

        // Four packed uint256s: buyPrice=91 sellPrice=89 buyPriceOffset=0 baseSpread=1.5 (6-dec micros).
        const val ENCODED_PRICE_CONFIG_INR =
            "0x" +
                "00000000000000000000000000000000000000000000000000000000056c8cc0" +
                "00000000000000000000000000000000000000000000000000000000054e0840" +
                "0000000000000000000000000000000000000000000000000000000000000000" +
                "000000000000000000000000000000000000000000000000000000000016e360"

        const val SUBGRAPH_OK_ONE_CIRCLE = """
            {"data":{"circles":[
              {"circleId":"1",
               "currency":"0x494e520000000000000000000000000000000000000000000000000000000000",
               "metrics":{"circleScore":"50","circleStatus":"active",
                 "scoreState":{"activeMerchantsCount":"4"}}}
            ]}}
        """
    }
}
