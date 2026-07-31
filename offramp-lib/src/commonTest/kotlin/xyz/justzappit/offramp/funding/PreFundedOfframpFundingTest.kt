// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.funding

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import xyz.justzappit.evm.rpc.BaseRpcClient
import xyz.justzappit.evm.types.Address
import xyz.justzappit.offramp.orchestrator.OfframpRequest
import xyz.justzappit.offramp.p2p.Usdc6
import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.math.bigIntegerOne
import xyz.justzappit.evm.math.bigIntegerValueOf
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PreFundedOfframpFundingTest {
    private val usdc = Address.parse("0xDABa329Ed949f28F64019f22c33c3B253B2Ded60")
    private val account = Address.parse("0xdD53a3Db48e5b69F34Abc1fA3156Dc3d0c269D5E")
    private val request =
        OfframpRequest(
            recipientUpi = "merchant@upi",
            usdcAmount = Usdc6(bigIntegerValueOf(1_000_000)),
            fiatAmount = Usdc6(bigIntegerValueOf(85_000_000)),
        )

    private fun fundingWithBalance(micros: BigInteger): PreFundedOfframpFunding {
        val resultHex = "0x" + micros.toString(HEX).padStart(WORD_HEX, '0')
        val client =
            HttpClient(
                MockEngine {
                    respond(
                        content = """{"jsonrpc":"2.0","id":1,"result":"$resultHex"}""",
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                },
            ) { install(ContentNegotiation) { json() } }
        return PreFundedOfframpFunding(BaseRpcClient(client, "http://mock/rpc"), usdc)
    }

    @Test
    fun `passes when balance covers the order`() =
        runTest {
            fundingWithBalance(bigIntegerValueOf(2_000_000)).ensureFunded(account, request, resumeHandle = null) {}
        }

    @Test
    fun `fails closed when balance is short surfacing both actual and required amounts`() =
        runTest {
            // What we're asserting (logic, not copy):
            //  - the unit throws IllegalStateException (Kotlin `check`), not silently returns
            //  - the message reflects the actual balance AND the required amount, so the user
            //    can act on the gap without having to log-scrape the underlying RPC trace
            val shortBalance = bigIntegerValueOf(500_000)
            var bridgeCallbackFired = false
            val e =
                assertFailsWith<IllegalStateException> {
                    fundingWithBalance(shortBalance).ensureFunded(account, request, resumeHandle = null) {
                        bridgeCallbackFired = true
                    }
                }
            val msg = e.message.orEmpty()
            assertTrue(
                msg.contains(shortBalance.toString()),
                "expected message to reflect actual balance $shortBalance, got: $msg",
            )
            assertTrue(
                msg.contains(request.usdcAmount.micros.toString()),
                "expected message to reflect required amount ${request.usdcAmount.micros}, got: $msg",
            )
            assertTrue(!bridgeCallbackFired, "fail-closed path must not invoke the bridge callback")
        }

    private companion object {
        const val HEX = 16
        const val WORD_HEX = 64
    }
}
