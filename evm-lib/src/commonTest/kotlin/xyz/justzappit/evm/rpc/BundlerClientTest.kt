// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.rpc

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
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

    private fun receiptResponse(operationSucceeded: Boolean, bundleStatus: String) =
        """{"jsonrpc":"2.0","id":1,"result":{"success":$operationSucceeded,""" +
            """"receipt":${receiptJson(bundleStatus)}}}"""

    private fun receiptJson(status: String) =
        """{"transactionHash":"$BUNDLE_TX_HASH","blockNumber":"0x1","status":"$status","gasUsed":"0x1"}"""

    private companion object {
        const val BUNDLE_TX_HASH = "0x2222222222222222222222222222222222222222222222222222222222222222"
        val ENTRY_POINT: Address = Address.parse("0x5FF137D4b0FDCD49DcA30c7CF57E578a026d2789")
        val USER_OP_HASH: TxHash =
            TxHash.fromHex("0x1111111111111111111111111111111111111111111111111111111111111111")
    }
}
