// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.p2p

import kotlinx.coroutines.test.runTest
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.math.bigIntegerOne
import xyz.justzappit.evm.math.bigIntegerValueOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FallbackOrderReaderTest {
    @Test
    fun `returns primary's result when primary succeeds`() =
        runTest {
            val primaryResult = snapshot(OrderSnapshot.Source.Subgraph)
            val fallback = neverCalled()
            val composite =
                FallbackOrderReader(
                    primary = constant(primaryResult),
                    fallback = fallback,
                )
            assertSame(primaryResult, composite.fetchOrder(bigIntegerOne))
        }

    @Test
    fun `falls through to secondary when primary throws`() =
        runTest {
            val secondaryResult = snapshot(OrderSnapshot.Source.OnChain)
            val warnings = mutableListOf<String>()
            val composite =
                FallbackOrderReader(
                    primary = throwing(IllegalStateException("subgraph 503")),
                    fallback = constant(secondaryResult),
                    logger = { msg, _ -> warnings += msg },
                )
            assertSame(secondaryResult, composite.fetchOrder(bigIntegerOne))
            assertEquals(1, warnings.size)
            assertTrue(warnings[0].contains("orderId=1"))
        }

    @Test
    fun `falls through when primary returns null`() =
        runTest {
            val secondaryResult = snapshot(OrderSnapshot.Source.OnChain)
            val composite =
                FallbackOrderReader(
                    primary = constant(null),
                    fallback = constant(secondaryResult),
                )
            assertSame(secondaryResult, composite.fetchOrder(bigIntegerOne))
        }

    @Test
    fun `absorbs both failures and returns null when primary and fallback both throw`() =
        runTest {
            // The reader must be total — if both layers fail, return null so the orchestrator's
            // poll loop re-emits the WaitingFor* status without bailing. A 30-min completion wait
            // can't be killed by a single bad RPC blip.
            val warnings = mutableListOf<String>()
            val composite =
                FallbackOrderReader(
                    primary = throwing(IllegalStateException("subgraph 503")),
                    fallback = throwing(IllegalStateException("rpc 502")),
                    logger = { msg, _ -> warnings += msg },
                )
            assertNull(composite.fetchOrder(bigIntegerOne))
            assertEquals(2, warnings.size, "both layers should have logged their failure")
            assertTrue(warnings.any { it.contains("Primary") })
            assertTrue(warnings.any { it.contains("Fallback") })
        }

    private fun snapshot(source: OrderSnapshot.Source) =
        OrderSnapshot(
            orderId = bigIntegerOne,
            status = OrderStatus.PLACED,
            orderType = OrderType.PAY,
            circleId = bigIntegerOne,
            userAddress = Address.ZERO,
            usdcAmount = Usdc6.ZERO,
            fiatAmount = Usdc6.ZERO,
            currencyHex = "0x" + "00".repeat(32),
            acceptedMerchantAddress = null,
            merchantPubKey = "",
            encryptedUserUpi = "",
            encryptedMerchantUpi = "",
            placedAtEpochSeconds = 1L,
            acceptedAtEpochSeconds = null,
            paidAtEpochSeconds = null,
            completedAtEpochSeconds = null,
            cancelledAtEpochSeconds = null,
            actualUsdcAmount = null,
            actualFiatAmount = null,
            placedTxHash = null,
            source = source,
        )

    private fun constant(snapshot: OrderSnapshot?) = OrderReadSource { _ -> snapshot }

    private fun throwing(cause: Throwable) = OrderReadSource { _ -> throw cause }

    private fun neverCalled() = OrderReadSource { _ -> error("fallback should not be called") }

    @Suppress("ktlint:standard:function-naming")
    private fun OrderReadSource(block: suspend (BigInteger) -> OrderSnapshot?) =
        object : OrderReadSource {
            override suspend fun fetchOrder(orderId: BigInteger): OrderSnapshot? = block(orderId)
        }
}
