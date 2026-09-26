// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 The Zapp Contributors

package xyz.justzappit.offramp.identity

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.math.bigIntegerValueOf
import xyz.justzappit.evm.rpc.BaseRpcClient
import xyz.justzappit.evm.rpc.RpcException
import xyz.justzappit.evm.rpc.TransactionReceipt
import xyz.justzappit.evm.signer.PreparedTransaction
import xyz.justzappit.evm.signer.TxSubmitter
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.types.ChainId
import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.evm.types.Wei
import xyz.justzappit.offramp.account.SubmittingAccount
import xyz.justzappit.offramp.apple.AppleIdentityClient
import xyz.justzappit.offramp.apple.AppleIdentityStatus
import xyz.justzappit.offramp.config.P2pNetworkConfig
import xyz.justzappit.offramp.config.P2pNetworks
import xyz.justzappit.offramp.p2p.CurrencyCode
import xyz.justzappit.offramp.reputation.IdentityCheck
import xyz.justzappit.offramp.reputation.ReputationReader
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class IdentityRecoveryTest {
    private val store = MemoryIdentityVerificationStore()
    private val submitter = RecordingSubmitter()
    private var now = NOW
    private var sessions = 0
    private var redemptions = 0
    private var failReads = false
    private var holdRedemption = false
    private val redemptionStarted = CompletableDeferred<Unit>()
    private val releaseRedemption = CompletableDeferred<Unit>()
    private val readBlocks = mutableListOf<String>()
    private val widgetHttp =
        HttpClient(
            MockEngine { request ->
                when (request.url.encodedPath) {
                    "/v1/widget/public-sessions" -> {
                        assertNotNull(store.get(key()), "authorization must be durable before opening the widget")
                        sessions++
                        respond("""{"widget_url":"https://widget.example/session"}""", HttpStatusCode.OK, JSON_HEADERS)
                    }

                    "/v1/widget/attestation" -> {
                        redemptions++
                        redemptionStarted.complete(Unit)
                        if (holdRedemption) releaseRedemption.await()
                        assertEquals(1, redemptions, "a one-time code must never be redeemed twice")
                        respond(
                            """{"nullifier":"0x${"11".repeat(32)}","limit":0,"expiry":"$EXPIRY",""" +
                                """"signature":"0x${"ab".repeat(65)}"}""",
                            HttpStatusCode.OK,
                            JSON_HEADERS,
                        )
                    }

                    else -> {
                        error("unexpected widget endpoint")
                    }
                }
            }
        )
    private val rpcHttp =
        HttpClient(
            MockEngine { request ->
                val payload =
                    Json
                        .parseToJsonElement(
                            (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                        ).jsonObject
                val params = payload.getValue("params").jsonArray
                val call = params[0].jsonObject
                val data = call.getValue("data").jsonPrimitive.content
                val simulation = data.startsWith("0x2bd54ab8") || data.startsWith("0x6d692e79")
                if (simulation) {
                    assertEquals(
                        WALLET.lowercaseHex,
                        call
                            .getValue("from")
                            .jsonPrimitive.content
                            .lowercase()
                    )
                    assertEquals(
                        P2pNetworks.SEPOLIA.reputationManagerAddress.lowercaseHex,
                        call
                            .getValue("to")
                            .jsonPrimitive.content
                            .lowercase()
                    )
                } else {
                    if (failReads) error("confirming read unavailable")
                    readBlocks += params[1].jsonPrimitive.content
                }
                val result = if (simulation) "0x" else "0x" + "1".padStart(64, '0').repeat(7)
                respond("""{"jsonrpc":"2.0","id":1,"result":"$result"}""", HttpStatusCode.OK, JSON_HEADERS)
            }
        ) {
            install(ContentNegotiation) { json() }
        }
    private val rpc = BaseRpcClient(rpcHttp, "https://rpc.example")

    @AfterTest
    fun close() {
        widgetHttp.close()
        rpcHttp.close()
    }

    @Test
    fun `cold start validates persisted state and completes at the receipt block`() =
        runTest {
            openSession()
            val statuses = driver().resume(success(), CurrencyCode.Brl).toList()
            assertIs<IdentityStatus.Done>(statuses.last())
            assertEquals(1, submitter.sends)
            assertTrue(readBlocks.isNotEmpty())
            assertTrue(readBlocks.all { it == BLOCK })
            assertNull(store.get(key()))
            assertIs<IdentityStatus.Failed>(driver().resume(success(), CurrencyCode.Brl).toList().last())
            assertEquals(1, redemptions)
        }

    @Test
    fun `unsolicited mismatched and expired returns never redeem`() =
        runTest {
            assertRejected(driver().resume(success(), CurrencyCode.Brl).toList())
            openSession()
            assertRejected(driver().resume(success().copy(state = null), CurrencyCode.Brl).toList())
            assertRejected(driver().resume(success().copy(state = "forged.BRL"), CurrencyCode.Brl).toList())
            assertRejected(driver().resume(success().copy(check = IdentityCheck.Passport), CurrencyCode.Brl).toList())
            assertRejected(driver().resume(success(), CurrencyCode.Pen).toList())
            val otherWallet = Address.parse("0x${"22".repeat(20)}")
            val otherNetwork = P2pNetworks.SEPOLIA.copy(chainId = ChainId(1))
            assertRejected(driver(wallet = otherWallet).resume(success(), CurrencyCode.Brl).toList())
            assertRejected(driver(network = otherNetwork).resume(success(), CurrencyCode.Brl).toList())
            assertEquals(0, redemptions)
            assertNotNull(store.get(key()), "a forged return must not discard the real session")
            now += SESSION_TTL
            assertRejected(driver().resume(success(), CurrencyCode.Brl).toList())
            assertEquals(0, redemptions)
        }

    @Test
    fun `explicit cancellation invalidates the pending session`() =
        runTest {
            openSession()
            driver().cancelWaiting(IdentityCheck.Liveness, CurrencyCode.Brl)
            assertRejected(driver().resume(success(), CurrencyCode.Brl).toList())
            assertEquals(0, redemptions)
        }

    @Test
    fun `errors also require the initiating session state`() =
        runTest {
            openSession()
            val error = success().copy(code = null, error = "cancelled", state = null)
            assertRejected(driver().resume(error, CurrencyCode.Brl).toList())
            assertNotNull(store.get(key()))
            val result = driver().resume(error.copy(state = STATE), CurrencyCode.Brl).toList().last()
            assertEquals(IdentityStatus.Failed(IdentityFailure.Cancelled), result)
            assertNull(store.get(key()))
        }

    @Test
    fun `a sponsorship failure retries the stored attestation after process recreation`() =
        runTest {
            openSession()
            submitter.failPreparation = true
            val failed = driver().resume(success(), CurrencyCode.Brl).toList().last()
            assertEquals(IdentityStatus.Failed(IdentityFailure.SponsorshipUnavailable), failed)
            assertNotNull(store.get(key())?.attestation)
            assertNull(store.get(key())?.transactionHash)
            driver().cancelWaiting(IdentityCheck.Liveness, CurrencyCode.Brl)
            assertNotNull(store.get(key()), "leaving the screen must retain the redeemed result")
            now += SESSION_TTL
            submitter.failPreparation = false
            assertEquals(IdentityCheck.Liveness, driver().recoverableCheck(CurrencyCode.Brl))
            assertIs<IdentityStatus.Done>(retry())
            assertEquals(1, sessions)
            assertEquals(1, redemptions)
            assertEquals(1, submitter.sends)
        }

    @Test
    fun `a lost send response recovers the saved hash even after attestation expiry`() =
        runTest {
            openSession()
            submitter.sendFailure =
                RpcException.TransportError(
                    "eth_sendUserOperation",
                    IllegalStateException("lost response"),
                )
            assertIs<IdentityStatus.Failed>(driver().resume(success(), CurrencyCode.Brl).toList().last())
            assertEquals(HASH.hex, store.get(key())?.transactionHash)
            assertEquals(NONCE.toString(), store.get(key())?.transactionNonce)
            now = EXPIRY + 1
            submitter.sendFailure = null
            assertIs<IdentityStatus.Done>(retry())
            assertEquals(HASH, submitter.restored?.hash)
            assertEquals(NONCE, submitter.restored?.nonce)
            assertEquals(1, submitter.sends, "an uncertain send must never be broadcast again")
            assertEquals(1, redemptions)
        }

    @Test
    fun `a definite send rejection releases the hash but retains the attestation`() =
        runTest {
            openSession()
            submitter.sendFailure = RpcException.InvalidParams("eth_sendUserOperation", "rejected")
            driver().resume(success(), CurrencyCode.Brl).toList()
            assertNull(store.get(key())?.transactionHash)
            assertNotNull(store.get(key())?.attestation)
            submitter.sendFailure = null
            assertIs<IdentityStatus.Done>(retry())
            assertEquals(2, submitter.sends)
            assertEquals(1, redemptions)
        }

    @Test
    fun `receipt timeout preserves ownership and retries only receipt polling`() =
        runTest {
            openSession()
            submitter.failReceipt = true
            driver().resume(success(), CurrencyCode.Brl).toList()
            assertNotNull(store.get(key())?.transactionHash)
            submitter.failReceipt = false
            assertIs<IdentityStatus.Done>(retry())
            assertEquals(1, submitter.sends)
            assertEquals(2, submitter.receiptPolls)
        }

    @Test
    fun `a failed confirming read retries only that read`() =
        runTest {
            openSession()
            failReads = true
            assertIs<IdentityStatus.Failed>(driver().resume(success(), CurrencyCode.Brl).toList().last())
            assertEquals(BLOCK, store.get(key())?.receiptBlock)
            failReads = false
            assertIs<IdentityStatus.Done>(retry())
            assertEquals(1, submitter.sends)
            assertEquals(1, submitter.receiptPolls)
            assertEquals(1, redemptions)
        }

    @Test
    fun `expired unsent attestations are discarded without a new send`() =
        runTest {
            openSession()
            submitter.failPreparation = true
            driver().resume(success(), CurrencyCode.Brl).toList()
            now = EXPIRY
            assertEquals(IdentityStatus.Failed(IdentityFailure.Expired), retry())
            assertNull(store.get(key()))
            assertEquals(0, submitter.sends)
        }

    @Test
    fun `a checkpoint write failure prevents broadcast and leaves the attestation retryable`() =
        runTest {
            openSession()
            store.failTransactionWrites = true
            assertIs<IdentityStatus.Failed>(driver().resume(success(), CurrencyCode.Brl).toList().last())
            assertEquals(0, submitter.sends)
            assertNotNull(store.get(key())?.attestation)
            assertNull(store.get(key())?.transactionHash)
            store.failTransactionWrites = false
            assertIs<IdentityStatus.Done>(retry())
            assertEquals(1, redemptions)
            assertEquals(1, submitter.sends)
        }

    @Test
    fun `Apple facade rejects concurrent runs and mismatched callbacks without consuming the signal`() =
        runTest {
            val client = AppleIdentityClient(driver())
            val ready = CompletableDeferred<Unit>()
            val statuses = mutableListOf<AppleIdentityStatus>()
            val job =
                launch {
                    client.verify(IdentityCheck.Liveness, "BRL", "nonce").collect {
                        statuses += it
                        if (it is AppleIdentityStatus.Ready) ready.complete(Unit)
                    }
                }
            ready.await()
            assertEquals(AppleIdentityStatus.Failed("Busy"), client.verify(IdentityCheck.Passport, "BRL", "other").toList().last())
            assertEquals(false, client.deliverReturn(IdentityCheck.Passport, "bad", null, STATE))
            assertEquals(false, client.deliverReturn(IdentityCheck.Liveness, "bad", null, "wrong.BRL"))
            assertTrue(client.deliverReturn(IdentityCheck.Liveness, "one-time-code", null, STATE))
            assertEquals(false, client.deliverReturn(IdentityCheck.Liveness, "one-time-code", null, STATE))
            job.join()
            client.awaitIdle()
            assertIs<AppleIdentityStatus.Done>(statuses.last())
            assertEquals(1, redemptions)
            assertEquals(1, submitter.sends)
        }

    @Test
    fun `Apple cancellation joins and invalidates only waiting authorization`() =
        runTest {
            val client = AppleIdentityClient(driver())
            val ready = CompletableDeferred<Unit>()
            val job =
                launch {
                    client.verify(IdentityCheck.Liveness, "BRL", "nonce").collect {
                        if (it is AppleIdentityStatus.Ready) ready.complete(Unit)
                    }
                }
            ready.await()
            job.cancelAndJoin()
            client.awaitIdle()
            client.cancelWaiting(IdentityCheck.Liveness, "BRL")
            assertEquals(
                AppleIdentityStatus.Failed("Rejected"),
                client.resume(IdentityCheck.Liveness, "one-time-code", null, STATE).toList().last()
            )
            assertEquals(0, redemptions)
            assertEquals(0, submitter.sends)
        }

    @Test
    fun `Apple native join waits for cancelled redemption to persist and retry reuses it`() =
        runTest {
            holdRedemption = true
            val client = AppleIdentityClient(driver())
            val ready = CompletableDeferred<Unit>()
            val job =
                launch {
                    client.verify(IdentityCheck.Liveness, "BRL", "nonce").collect {
                        if (it is AppleIdentityStatus.Ready) ready.complete(Unit)
                    }
                }
            ready.await()
            assertTrue(client.deliverReturn(IdentityCheck.Liveness, "one-time-code", null, STATE))
            redemptionStarted.await()
            job.cancel()
            val joined = async { client.awaitIdle() }
            runCurrent()
            assertEquals(false, joined.isCompleted)
            releaseRedemption.complete(Unit)
            job.join()
            joined.await()
            client.cancelWaiting(IdentityCheck.Liveness, "BRL")
            assertNotNull(store.get(key())?.attestation)
            assertEquals(0, submitter.sends)
            assertIs<AppleIdentityStatus.Done>(client.verify(IdentityCheck.Liveness, "BRL", "new-nonce").toList().last())
            assertEquals(1, redemptions)
            assertEquals(1, submitter.sends)
        }

    @Test
    fun `consumed callbacks cannot retry submission even after cancellation`() =
        runTest {
            openSession()
            submitter.failPreparation = true
            assertIs<IdentityStatus.Failed>(driver().resume(success(), CurrencyCode.Brl).toList().last())
            assertNotNull(store.get(key())?.attestation)
            driver().cancelWaiting(IdentityCheck.Liveness, CurrencyCode.Brl)
            submitter.failPreparation = false
            assertRejected(driver().resume(success(), CurrencyCode.Brl).toList())
            assertEquals(0, submitter.sends)
            assertEquals(1, redemptions)
            assertNotNull(store.get(key())?.attestation)
            assertIs<IdentityStatus.Done>(retry())
            assertEquals(1, redemptions)
            assertEquals(1, submitter.sends)
        }

    private suspend fun TestScope.openSession() {
        val ready = CompletableDeferred<Unit>()
        val job =
            backgroundScope.launch {
                driver().verify(IdentityCheck.Liveness, CurrencyCode.Brl, "nonce", IdentityReturnSignal()).collect {
                    if (it is IdentityStatus.Ready) ready.complete(Unit)
                }
            }
        ready.await()
        assertNotNull(store.get(key()))
        job.cancelAndJoin() // Process loss, not an explicit user cancellation.
    }

    private suspend fun retry(): IdentityStatus =
        driver().verify(IdentityCheck.Liveness, CurrencyCode.Brl, "new-nonce", IdentityReturnSignal()).toList().last()

    private fun assertRejected(statuses: List<IdentityStatus>) {
        assertEquals(IdentityStatus.Failed(IdentityFailure.Rejected), statuses.last())
    }

    private fun success() = IdentityReturn(IdentityCheck.Liveness, "one-time-code", null, STATE)

    private fun key(): String =
        "${P2pNetworks.SEPOLIA.chainId.value}_${P2pNetworks.SEPOLIA.reputationManagerAddress.lowercaseHex}_" +
            "${WALLET.lowercaseHex}_Liveness"

    private fun driver(wallet: Address = WALLET, network: P2pNetworkConfig = P2pNetworks.SEPOLIA) =
        IdentityVerificationDriver(
            widget = IdentityWidgetClient(widgetHttp),
            services =
                IdentityServices(
                    IdentityService("https://widget.example", "tenant"),
                    IdentityService("https://widget.example", "passport")
                ),
            returnUrl = { "zcash://return" },
            reputationReader = ReputationReader(rpc, network, blockPollAttempts = 1),
            resolveAccount = { SubmittingAccount(wallet, submitter) },
            store = store,
            nowSeconds = { now },
            rpc = rpc,
            network = network,
        )

    private inner class RecordingSubmitter : TxSubmitter {
        var sends = 0
        var receiptPolls = 0
        var failPreparation = false
        var failReceipt = false
        var sendFailure: Exception? = null
        var restored: PreparedTransaction? = null

        override suspend fun sendTransaction(
            to: Address,
            value: Wei,
            data: ByteArray,
            beforeBroadcast: suspend (PreparedTransaction) -> Unit,
        ): TxHash {
            if (failPreparation) error("paymaster unavailable")
            beforeBroadcast(PreparedTransaction(HASH, NONCE))
            assertEquals(HASH.hex, store.get(key())?.transactionHash, "hash must be durable before broadcast")
            sends++
            sendFailure?.let { throw it }
            return HASH
        }

        override suspend fun restorePendingTransaction(hash: TxHash?, nonce: BigInteger?) {
            restored = PreparedTransaction(requireNotNull(hash), requireNotNull(nonce))
        }

        override suspend fun receiptIfIncluded(txHash: TxHash): TransactionReceipt = receipt()

        override suspend fun awaitReceipt(txHash: TxHash): TransactionReceipt {
            assertEquals(HASH, txHash)
            receiptPolls++
            if (failReceipt) error("receipt timeout")
            return receipt()
        }

        private fun receipt() = TransactionReceipt(HASH.hex, BLOCK, "0x1", "0x1")
    }

    private companion object {
        const val NOW = 1_750_000_000L
        const val EXPIRY = NOW + 7_200L
        const val SESSION_TTL = 1_800L
        const val STATE = "nonce.BRL"
        const val BLOCK = "0x123"
        val WALLET = Address.parse("0x${"11".repeat(20)}")
        val HASH = TxHash.fromHex("0x${"33".repeat(32)}")
        val NONCE = bigIntegerValueOf(4)
        val JSON_HEADERS = headersOf(HttpHeaders.ContentType, "application/json")
    }
}
