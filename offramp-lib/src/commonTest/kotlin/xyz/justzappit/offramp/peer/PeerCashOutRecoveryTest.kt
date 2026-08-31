// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.peer

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.rpc.BaseRpcClient
import xyz.justzappit.evm.rpc.EvmLog
import xyz.justzappit.evm.rpc.RpcException
import xyz.justzappit.evm.rpc.RpcHttpClient
import xyz.justzappit.evm.rpc.TransactionReceipt
import xyz.justzappit.evm.signer.PreparedTransaction
import xyz.justzappit.evm.signer.TxSubmitter
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.evm.types.Wei
import xyz.justzappit.offramp.account.AllowanceTransactionGuard
import xyz.justzappit.offramp.apple.ApplePeerRecoveryStorageException
import xyz.justzappit.offramp.funding.NoRouteOfframpTopUp
import xyz.justzappit.offramp.p2p.Usdc6
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PeerCashOutRecoveryTest {
    private val http =
        RpcHttpClient.create(
            engine =
                MockEngine {
                    respond(
                        """{"jsonrpc":"2.0","id":1,"result":"0x${"00".repeat(28)}05f5e100"}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                },
            config = RpcHttpClient.Config(maxRetries = 0),
        )
    private val rpc = BaseRpcClient(http, "http://mock/rpc")

    @AfterTest
    fun close() {
        http.close()
    }

    @Test
    fun `reconciliation resolves the exact persisted submission identity`() =
        runTest {
            val submitter = ScriptedSubmitter(Mode.SUCCESS)
            val depositId = orchestrator(submitter).resolveCheckpoint(checkpoint(submissionHash = SUBMISSION))

            assertEquals(listOf(SUBMISSION), submitter.awaited)
            assertEquals(DEPOSIT_ID, depositId)
        }

    @Test
    fun `a legacy block floor fails closed instead of claiming a similar later order`() =
        runTest {
            val submitter = ScriptedSubmitter(Mode.SUCCESS)
            val error =
                assertFailsWith<PeerException> {
                    orchestrator(submitter).resolveCheckpoint(checkpoint(legacyBlockFloor = "123"))
                }

            assertEquals(PeerErrorCode.TRANSACTION_SUBMISSION_UNKNOWN, error.error.code)
            assertTrue(submitter.awaited.isEmpty())
        }

    @Test
    fun `a proven preparation failure has no marker and releases the amount`() =
        runTest {
            val statuses = orchestrator(ScriptedSubmitter(Mode.PREPARATION_FAIL)).createOrder(request()).toList()
            val failed = assertIs<PeerCashOutStatus.Failed>(statuses.last())

            assertEquals(PeerCashOutStep.CREATING_DEPOSIT, failed.step)
            assertEquals(PeerErrorCode.TRANSACTION_FAILED, failed.error.code)
            assertTrue(failed.error.nothingEscrowed)
            assertTrue(statuses.none { it is PeerCashOutStatus.CreatingDeposit })
        }

    @Test
    fun `a lost send response retains the pre persisted exact identity`() =
        runTest {
            val statuses = orchestrator(ScriptedSubmitter(Mode.SEND_RESPONSE_LOST)).createOrder(request()).toList()
            val creating = statuses.filterIsInstance<PeerCashOutStatus.CreatingDeposit>().single()
            val failed = assertIs<PeerCashOutStatus.Failed>(statuses.last())

            assertEquals(SUBMISSION, creating.submissionHash)
            assertEquals(null, creating.txHash)
            assertEquals(PeerErrorCode.TRANSACTION_SUBMISSION_UNKNOWN, failed.error.code)
            assertEquals(SUBMISSION, failed.txHash)
            assertEquals(false, failed.error.nothingEscrowed)
        }

    @Test
    fun `a definite bundler rejection retires the prepared marker`() =
        runTest {
            val statuses = orchestrator(ScriptedSubmitter(Mode.SEND_REJECTED)).createOrder(request()).toList()
            val failed = assertIs<PeerCashOutStatus.Failed>(statuses.last())

            assertTrue(statuses.any { it is PeerCashOutStatus.CreatingDeposit })
            assertEquals(PeerErrorCode.TRANSACTION_FAILED, failed.error.code)
            assertTrue(failed.error.nothingEscrowed)
            assertTrue(failed.error.allowsManualRetry)
        }

    @Test
    fun `a marker consumer failure prevents broadcast and remains a proven pre send failure`() =
        runTest {
            val submitter = MarkerBoundarySubmitter()

            val error =
                assertFailsWith<PeerException> {
                    orchestrator(submitter).createOrder(request()).collect { status ->
                        if (status is PeerCashOutStatus.CreatingDeposit && status.txHash == null) {
                            throw ApplePeerRecoveryStorageException("checkpoint write failed")
                        }
                    }
                }

            assertEquals(0, submitter.createBroadcasts)
            assertEquals(PeerErrorCode.INITIALIZATION_FAILED, error.error.code)
            assertTrue(error.error.nothingEscrowed)
            assertTrue(error.error.allowsManualRetry)
        }

    @Test
    fun `two collected Peer flows cannot interleave exact approvals and deposits`() =
        runTest {
            val guard = AllowanceTransactionGuard()
            val events = mutableListOf<String>()
            val firstCreateSent = CompletableDeferred<Unit>()
            val firstCreateReceipt = CompletableDeferred<Unit>()
            val firstSubmitter = AllowanceSequenceSubmitter("first", events, firstCreateSent, firstCreateReceipt)
            val secondSubmitter = AllowanceSequenceSubmitter("second", events)

            val first = async { orchestrator(firstSubmitter, guard).createOrder(request()).toList() }
            firstCreateSent.await()
            val second = async { orchestrator(secondSubmitter, guard).createOrder(request()).toList() }
            yield()

            assertEquals(listOf("first approve", "first create"), events)
            firstCreateReceipt.complete(Unit)
            awaitAll(first, second)
            assertEquals(
                listOf(
                    "first approve",
                    "first create",
                    "first create receipt",
                    "second approve",
                    "second create",
                    "second create receipt",
                ),
                events,
            )
        }

    private fun orchestrator(
        submitter: TxSubmitter,
        allowanceTransactions: AllowanceTransactionGuard = AllowanceTransactionGuard(),
    ) =
        PeerCashOutOrchestratorImpl(
            network = PeerNetworks.PRODUCTION,
            account = ACCOUNT,
            txSubmitter = submitter,
            allowanceTransactions = allowanceTransactions,
            rpcClient = rpc,
            curatorClient = PeerCuratorClient(http, "http://mock/curator"),
            indexerClient = PeerIndexerClient(http, "http://mock/indexer"),
            topUp = NoRouteOfframpTopUp(),
            pollIntervalMillis = 0L,
        )

    private fun request() =
        PeerCashOutRequest(
            platform = PeerPlatform.REVOLUT,
            handle = null,
            currencies = listOf(PeerCurrency.EUR),
            amount = AMOUNT,
            cachedPayeeHash = PAYEE_HASH,
        )

    private fun checkpoint(
        submissionHash: TxHash? = null,
        legacyBlockFloor: String? = null,
    ) = PeerCashOutCheckpoint(
        id = PeerCashOutId.of(ByteArray(PeerCashOutId.SIZE_BYTES) { 1 }),
        platform = PeerPlatform.REVOLUT,
        currencies = listOf(PeerCurrency.EUR),
        payeeHashHex = PAYEE_HASH.hex,
        amountMicroDecimal = AMOUNT.micros.toString(),
        createDepositSubmissionHash = submissionHash,
        blockBeforeCreateDeposit = legacyBlockFloor,
        createdAtMillis = 1L,
    )

    private enum class Mode {
        SUCCESS,
        PREPARATION_FAIL,
        SEND_REJECTED,
        SEND_RESPONSE_LOST,
    }

    private class ScriptedSubmitter(
        private val mode: Mode,
    ) : TxSubmitter {
        val awaited = mutableListOf<TxHash>()
        private var sends = 0

        override suspend fun sendTransaction(
            to: Address,
            value: Wei,
            data: ByteArray,
            beforeBroadcast: suspend (PreparedTransaction) -> Unit,
        ): TxHash {
            sends++
            if (sends == CREATE_SEND && mode == Mode.PREPARATION_FAIL) error("estimation failed")
            val hash = if (sends == CREATE_SEND) SUBMISSION else APPROVAL
            beforeBroadcast(PreparedTransaction(hash, BigInteger((sends - 1).toString())))
            if (sends == CREATE_SEND && mode == Mode.SEND_REJECTED) {
                throw RpcException.Unknown(
                    method = "eth_sendUserOperation",
                    code = -32602,
                    raw = "rejected",
                    errorMessage = "AA23 reverted",
                )
            }
            if (sends == CREATE_SEND && mode == Mode.SEND_RESPONSE_LOST) error("connection reset")
            return hash
        }

        override suspend fun awaitReceipt(txHash: TxHash): TransactionReceipt {
            awaited += txHash
            return if (txHash == SUBMISSION) depositReceipt() else successReceipt(txHash)
        }

        override suspend fun receiptIfIncluded(txHash: TxHash): TransactionReceipt? = awaitReceipt(txHash)

        override suspend fun restorePendingTransaction(hash: TxHash?, nonce: BigInteger?) = Unit

        private companion object {
            const val CREATE_SEND = 2
        }
    }

    private class AllowanceSequenceSubmitter(
        private val name: String,
        private val events: MutableList<String>,
        private val createSent: CompletableDeferred<Unit>? = null,
        private val createReceiptGate: CompletableDeferred<Unit>? = null,
    ) : TxSubmitter {
        private var sends = 0
        private val approvalHash = hashOf(name, "approval")
        private val createHash = hashOf(name, "create")

        override suspend fun sendTransaction(
            to: Address,
            value: Wei,
            data: ByteArray,
            beforeBroadcast: suspend (PreparedTransaction) -> Unit,
        ): TxHash {
            sends++
            val isCreate = sends == CREATE_SEND
            val hash = if (isCreate) createHash else approvalHash
            events += if (isCreate) "$name create" else "$name approve"
            beforeBroadcast(PreparedTransaction(hash, BigInteger((sends - 1).toString())))
            if (isCreate) createSent?.complete(Unit)
            return hash
        }

        override suspend fun awaitReceipt(txHash: TxHash): TransactionReceipt {
            if (txHash == createHash) {
                createReceiptGate?.await()
                events += "$name create receipt"
            }
            // Deliberately no DepositReceived log: the flow terminates after the receipt instead of
            // entering its endless order poll, while still holding the guard for the full window.
            return successReceipt(txHash)
        }

        override suspend fun receiptIfIncluded(txHash: TxHash): TransactionReceipt? = awaitReceipt(txHash)

        override suspend fun restorePendingTransaction(hash: TxHash?, nonce: BigInteger?) = Unit

        private companion object {
            const val CREATE_SEND = 2

            fun hashOf(name: String, operation: String): TxHash {
                val byte = (name + operation).encodeToByteArray().fold(0) { hash, value -> hash * 31 + value }
                return TxHash.fromHex("0x${byte.toUByte().toString(16).padStart(2, '0').repeat(TxHash.LEN)}")
            }
        }
    }

    private class MarkerBoundarySubmitter : TxSubmitter {
        var createBroadcasts = 0
        private var sends = 0

        override suspend fun sendTransaction(
            to: Address,
            value: Wei,
            data: ByteArray,
            beforeBroadcast: suspend (PreparedTransaction) -> Unit,
        ): TxHash {
            sends++
            val isCreate = sends == CREATE_SEND
            val hash = if (isCreate) SUBMISSION else APPROVAL
            beforeBroadcast(PreparedTransaction(hash, BigInteger((sends - 1).toString())))
            if (isCreate) createBroadcasts++
            return hash
        }

        override suspend fun awaitReceipt(txHash: TxHash): TransactionReceipt = successReceipt(txHash)

        override suspend fun receiptIfIncluded(txHash: TxHash): TransactionReceipt? = awaitReceipt(txHash)

        override suspend fun restorePendingTransaction(hash: TxHash?, nonce: BigInteger?) = Unit

        private companion object {
            const val CREATE_SEND = 2
        }
    }

    private companion object {
        val ACCOUNT: Address = Address.parse("0x0000000000000000000000000000000000000001")
        val AMOUNT: Usdc6 = Usdc6.ofMicros(20_000_000L)
        val PAYEE_HASH: PayeeHash = PayeeHash.parse("0x${"11".repeat(TxHash.LEN)}")
        val APPROVAL: TxHash = TxHash.fromHex("0x${"aa".repeat(TxHash.LEN)}")
        val SUBMISSION: TxHash = TxHash.fromHex("0x${"bb".repeat(TxHash.LEN)}")
        val DEPOSIT_ID: PeerDepositId =
            PeerDepositId.of(PeerNetworks.PRODUCTION.escrowAddress, BigInteger("7"))

        fun successReceipt(hash: TxHash) =
            TransactionReceipt(
                transactionHash = hash.hex,
                blockNumber = "0x1",
                status = "0x1",
                gasUsed = "0x1",
            )

        fun depositReceipt() =
            successReceipt(SUBMISSION).copy(
                logs =
                    listOf(
                        EvmLog(
                            address = PeerNetworks.PRODUCTION.escrowAddress.checksumHex,
                            topics =
                                listOf(
                                    PeerDepositReceipt.DEPOSIT_RECEIVED_TOPIC,
                                    "0x${"7".padStart(TxHash.LEN * 2, '0')}",
                                ),
                            data = "0x",
                            blockNumber = "0x1",
                            transactionHash = SUBMISSION.hex,
                            logIndex = "0x0",
                        ),
                    ),
            )
    }
}
