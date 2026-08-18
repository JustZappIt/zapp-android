// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.p2p

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.math.bigIntegerOne
import xyz.justzappit.evm.math.bigIntegerValueOf
import xyz.justzappit.evm.types.Address
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubgraphOrderReaderTest {
    private var nextResponse: String = ""
    private val client =
        HttpClient(
            MockEngine {
                respond(nextResponse, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        ) { install(ContentNegotiation) { json() } }

    private val reader =
        SubgraphOrderReader(
            subgraph = SubgraphClient(client, "http://mock/graph"),
        )

    @AfterTest
    fun tearDown() {
        client.close()
    }

    @Test
    fun `parses a fresh PAY order with no merchant yet`() =
        runTest {
            nextResponse = ORDER_61_FRESH
            val snapshot = reader.fetchOrder(bigIntegerValueOf(61))!!
            assertEquals(bigIntegerValueOf(61), snapshot.orderId)
            assertEquals(OrderType.PAY, snapshot.orderType)
            assertEquals(OrderStatus.PLACED, snapshot.status)
            assertEquals(bigIntegerOne, snapshot.circleId)
            assertEquals(Usdc6.ofMicros(10_000_000), snapshot.usdcAmount)
            assertEquals(Usdc6.ofMicros(890_000_000), snapshot.fiatAmount)
            // "0x00000000" returned by subgraph means zero-address — should normalize to null.
            assertNull(snapshot.acceptedMerchantAddress)
            assertEquals("", snapshot.merchantPubKey)
            assertEquals(1_779_350_162L, snapshot.placedAtEpochSeconds)
            assertNull(snapshot.acceptedAtEpochSeconds)
            assertNull(snapshot.paidAtEpochSeconds)
            assertNull(snapshot.completedAtEpochSeconds)
            assertNull(snapshot.cancelledAtEpochSeconds)
            assertEquals(Usdc6.ofMicros(10_125_000), snapshot.actualUsdcAmount)
            assertEquals(Usdc6.ofMicros(890_000_000), snapshot.actualFiatAmount)
            assertEquals(
                xyz.justzappit.evm.types.TxHash
                    .fromHex("0xee7f94f3e2b719f79dc22fae7fef4ee95694996307c31f9643d0bcb79eb5eb71"),
                snapshot.placedTxHash,
            )
            assertEquals(OrderSnapshot.Source.Subgraph, snapshot.source)
            assertTrue(!snapshot.isAccepted)
        }

    @Test
    fun `parses an accepted order with merchant pubkey set`() =
        runTest {
            nextResponse = ORDER_99_ACCEPTED
            val snapshot = reader.fetchOrder(bigIntegerValueOf(99))!!
            assertEquals(OrderStatus.ACCEPTED, snapshot.status)
            assertEquals(Address.parse("0x0000000000000000000000000000000000abcdef"), snapshot.acceptedMerchantAddress)
            assertEquals(MERCHANT_PUBKEY, snapshot.merchantPubKey)
            assertTrue(snapshot.isAccepted)
            assertEquals(1_779_400_000L, snapshot.acceptedAtEpochSeconds)
        }

    @Test
    fun `returns null when the subgraph has not indexed the order yet`() =
        runTest {
            nextResponse = """{"data":{"orders_collection":[]}}"""
            assertNull(reader.fetchOrder(bigIntegerValueOf(9999)))
        }

    @Test
    fun `propagates GraphQL errors as exceptions`() =
        runTest {
            nextResponse = """{"errors":[{"message":"deployment unavailable"}]}"""
            try {
                reader.fetchOrder(bigIntegerValueOf(1))
                error("expected the GraphQL error to propagate")
            } catch (e: IllegalStateException) {
                assertTrue(e.message!!.contains("deployment unavailable"))
            }
        }

    @Test
    fun `parses a cancelled order`() =
        runTest {
            nextResponse = ORDER_42_CANCELLED
            val snapshot = reader.fetchOrder(bigIntegerValueOf(42))!!
            assertEquals(OrderStatus.CANCELLED, snapshot.status)
            assertEquals(1_779_500_000L, snapshot.cancelledAtEpochSeconds)
        }

    companion object {
        // Captured from the live Sepolia subgraph on 2026-05-21 (order #61).
        private const val ORDER_61_FRESH = """
            {"data":{"orders_collection":[{
              "orderId":"61","type":2,"status":0,"circleId":"1",
              "userAddress":"0x8159db107c0cf6e60338c66e43c334842cf6e76e",
              "usdcAmount":"10000000","fiatAmount":"890000000",
              "currency":"0x494e520000000000000000000000000000000000000000000000000000000000",
              "placedAt":"1779350162","acceptedAt":"0","paidAt":"0","completedAt":"0","cancelledAt":"0",
              "acceptedMerchantAddress":"0x00000000","pubkey":"","encUpi":"","encMerchantUpi":"",
              "actualUsdcAmount":"10125000","actualFiatAmount":"890000000",
              "blockNumber":"41790937","blockTimestamp":"1779350162",
              "transactionHash":"0xee7f94f3e2b719f79dc22fae7fef4ee95694996307c31f9643d0bcb79eb5eb71"
            }]}}
        """

        private const val MERCHANT_PUBKEY =
            "1b84c5567b126440995d3ed5aaba0565d71e1834604819ff9c17f5e9d5dd078f" +
                "70beaf8f588b541507fed6a642c5ab42dfdf8120a7f639de5122d47a69a8e8d1"

        private const val ORDER_99_ACCEPTED = """
            {"data":{"orders_collection":[{
              "orderId":"99","type":2,"status":1,"circleId":"1",
              "userAddress":"0x8159db107c0cf6e60338c66e43c334842cf6e76e",
              "usdcAmount":"5000000","fiatAmount":"445000000",
              "currency":"0x494e520000000000000000000000000000000000000000000000000000000000",
              "placedAt":"1779399000","acceptedAt":"1779400000","paidAt":"0","completedAt":"0","cancelledAt":"0",
              "acceptedMerchantAddress":"0xabcdef","pubkey":"$MERCHANT_PUBKEY",
              "encUpi":"","encMerchantUpi":"",
              "actualUsdcAmount":"5062500","actualFiatAmount":"445000000",
              "blockNumber":"41800000","blockTimestamp":"1779399000",
              "transactionHash":"0x000000000000000000000000000000000000000000000000000000000000abcd"
            }]}}
        """

        private const val ORDER_42_CANCELLED = """
            {"data":{"orders_collection":[{
              "orderId":"42","type":2,"status":4,"circleId":"1",
              "userAddress":"0x8159db107c0cf6e60338c66e43c334842cf6e76e",
              "usdcAmount":"1000000","fiatAmount":"89000000",
              "currency":"0x494e520000000000000000000000000000000000000000000000000000000000",
              "placedAt":"1779490000","acceptedAt":"0","paidAt":"0","completedAt":"0","cancelledAt":"1779500000",
              "acceptedMerchantAddress":"0x","pubkey":"","encUpi":"","encMerchantUpi":"",
              "actualUsdcAmount":"0","actualFiatAmount":"0",
              "blockNumber":"41810000","blockTimestamp":"1779490000",
              "transactionHash":"0x000000000000000000000000000000000000000000000000000000000000dead"
            }]}}
        """
    }
}
