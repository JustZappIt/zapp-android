// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.rpc

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import xyz.justzappit.evm.math.bigIntegerZero
import xyz.justzappit.evm.signer.UserOperationV06
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.types.ChainId
import xyz.justzappit.evm.types.TxHash
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BundlerClientTest {
    private var nextResponse: String = ""

    private val http =
        RpcHttpClient.create(
            engine =
                MockEngine {
                    respond(
                        content = nextResponse,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                },
            config = RpcHttpClient.Config(maxRetries = 0),
        )

    private val bundler =
        BundlerClient(
            httpClient = http,
            bundlerUrl = "http://mock/bundler",
            entryPoint = ENTRY_POINT,
            chainId = ChainId.BASE_MAINNET,
        )

    @AfterTest
    fun shutdown() {
        http.close()
    }

    @Test
    fun `a pending userOp has no receipt yet`() =
        runTest {
            nextResponse = """{"jsonrpc":"2.0","id":1,"result":null}"""

            assertNull(bundler.getUserOperationReceipt(USER_OP_HASH))
        }

    @Test
    fun `a succeeded userOp keeps the bundle receipt as-is`() =
        runTest {
            nextResponse = receiptResponse(operationSucceeded = true, bundleStatus = "0x1")

            val receipt = bundler.getUserOperationReceipt(USER_OP_HASH)

            assertTrue(receipt!!.success)
            assertEquals(BUNDLE_TX_HASH, receipt.transactionHash)
        }

    @Test
    fun `a reverted userOp inside a mined bundle does not read as success`() =
        runTest {
            // EntryPoint catches the inner revert, so the bundle transaction's own status is still
            // 0x1 — only the top-level `success` distinguishes the two.
            nextResponse = receiptResponse(operationSucceeded = false, bundleStatus = "0x1")

            val receipt = bundler.getUserOperationReceipt(USER_OP_HASH)

            assertFalse(receipt!!.success)
            assertEquals(BUNDLE_TX_HASH, receipt.transactionHash)
        }

    @Test
    fun `receipt exposes only logs scoped to the requested userOp`() =
        runTest {
            nextResponse =
                """{"jsonrpc":"2.0","id":1,"result":{"success":true,"logs":[${logJson("0x02")}],""" +
                """"receipt":${receiptJson("0x1", logs = logJson("0x01"))}}}"""

            val receipt = bundler.getUserOperationReceipt(USER_OP_HASH)

            assertEquals(listOf("0x02"), receipt!!.logs.map { it.data })
        }

    @Test
    fun `a bundler that omits success fails closed`() =
        runTest {
            nextResponse =
                """{"jsonrpc":"2.0","id":1,"result":{"receipt":${receiptJson("0x1")}}}"""

            assertFailsWith<IllegalStateException> { bundler.getUserOperationReceipt(USER_OP_HASH) }
        }

    @Test
    fun `a non-null bundler result without a receipt fails closed`() =
        runTest {
            nextResponse = """{"jsonrpc":"2.0","id":1,"result":{"success":true}}"""

            assertFailsWith<IllegalStateException> { bundler.getUserOperationReceipt(USER_OP_HASH) }
        }

    @Test
    fun `a bundler that returns a malformed success verdict fails closed`() =
        runTest {
            nextResponse =
                """{"jsonrpc":"2.0","id":1,"result":{"success":"true","receipt":${receiptJson("0x1")}}}"""

            assertFailsWith<IllegalStateException> { bundler.getUserOperationReceipt(USER_OP_HASH) }
        }

    @Test
    fun `a lost send response is never retried into a misleading rpc rejection`() =
        runTest {
            var requests = 0
            val retryingHttp =
                RpcHttpClient.create(
                    engine =
                        MockEngine {
                            requests++
                            if (requests == 1) throw IOException("accepted, response lost")
                            respond(
                                """{"jsonrpc":"2.0","id":1,"error":{"code":-32000,"message":"already known"}}""",
                                HttpStatusCode.OK,
                                headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        },
                    config =
                        RpcHttpClient.Config(
                            maxRetries = 3,
                            maxBackoffMillis = 0,
                            randomJitterMillis = 0,
                        ),
                )
            val retryingBundler =
                BundlerClient(
                    httpClient = retryingHttp,
                    bundlerUrl = "http://mock/bundler",
                    entryPoint = ENTRY_POINT,
                    chainId = ChainId.BASE_MAINNET,
                )
            try {
                assertFailsWith<RpcException.TransportError> {
                    retryingBundler.sendUserOperation(USER_OPERATION)
                }
                assertEquals(1, requests)
            } finally {
                retryingHttp.close()
            }
        }

    private fun receiptResponse(operationSucceeded: Boolean, bundleStatus: String) =
        """{"jsonrpc":"2.0","id":1,"result":{"success":$operationSucceeded,"logs":[],""" +
            """"receipt":${receiptJson(bundleStatus)}}}"""

    private fun receiptJson(status: String, logs: String? = null) =
        """{"transactionHash":"$BUNDLE_TX_HASH","blockNumber":"0x1","status":"$status","gasUsed":"0x1",""" +
            """"logs":[${logs.orEmpty()}]}"""

    private fun logJson(data: String) =
        """{"address":"0x1111111111111111111111111111111111111111","topics":[],"data":"$data",""" +
            """"blockNumber":"0x1","transactionHash":"$BUNDLE_TX_HASH","logIndex":"0x0"}"""

    private companion object {
        const val BUNDLE_TX_HASH = "0x2222222222222222222222222222222222222222222222222222222222222222"
        val ENTRY_POINT: Address = Address.parse("0x5FF137D4b0FDCD49DcA30c7CF57E578a026d2789")
        val USER_OP_HASH: TxHash =
            TxHash.fromHex("0x1111111111111111111111111111111111111111111111111111111111111111")
        val USER_OPERATION =
            UserOperationV06(
                sender = Address.parse("0x1111111111111111111111111111111111111111"),
                nonce = bigIntegerZero,
                initCode = byteArrayOf(),
                callData = byteArrayOf(),
                callGasLimit = bigIntegerZero,
                verificationGasLimit = bigIntegerZero,
                preVerificationGas = bigIntegerZero,
                maxFeePerGas = bigIntegerZero,
                maxPriorityFeePerGas = bigIntegerZero,
                paymasterAndData = byteArrayOf(),
                signature = byteArrayOf(),
            )
    }
}
