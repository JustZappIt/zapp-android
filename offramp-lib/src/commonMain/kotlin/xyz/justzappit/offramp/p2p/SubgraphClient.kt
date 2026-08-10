// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.p2p

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

class SubgraphClient(
    private val httpClient: HttpClient,
    private val subgraphUrl: String,
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    suspend fun circlesForRouting(currencyBytes32Hex: String): List<CircleForRouting> {
        val data =
            query(
                query = CIRCLES_FOR_ROUTING_QUERY,
                variables = buildJsonObject { put("currency", currencyBytes32Hex) },
            )
        val circles = data["circles"]?.jsonArray ?: error("subgraph response missing 'circles'")
        return circles
            .map { json.decodeFromJsonElement(CircleForRouting.serializer(), it) }
            .filter {
                (
                    it.metrics.scoreState.activeMerchantsCount
                        .toIntOrNull() ?: 0
                ) > 0
            }
    }

    suspend fun rawOrderById(orderId: String): JsonObject? {
        val data =
            query(
                query = ORDER_BY_ID_QUERY,
                variables = buildJsonObject { put("orderId", orderId) },
            )
        val orders = data["orders_collection"]?.jsonArray ?: return null
        return orders.firstOrNull()?.jsonObject
    }

    /**
     * One page of orders for [userAddress] sorted by `placedAt desc`. Mirrors
     * `OrdersCollectionWithDateFilter` in `user-app-client`; callers walk pages until a page
     * returns fewer than [first] rows.
     */
    suspend fun ordersForUser(userAddress: String, first: Int, skip: Int): List<JsonObject> {
        val data =
            query(
                query = USER_ORDERS_QUERY,
                variables =
                    buildJsonObject {
                        put("userAddress", userAddress.lowercase())
                        put("first", first)
                        put("skip", skip)
                    },
            )
        val orders = data["orders_collection"]?.jsonArray ?: return emptyList()
        return orders.map { it.jsonObject }
    }

    private suspend fun query(query: String, variables: JsonElement): JsonObject {
        val payload =
            buildJsonObject {
                put("query", query)
                put("variables", variables)
            }
        val response: JsonObject =
            httpClient
                .post(subgraphUrl) {
                    contentType(ContentType.Application.Json)
                    setBody(payload)
                }.body()

        response["errors"]?.let { errs -> error("subgraph errors: $errs") }
        return response["data"]?.jsonObject ?: error("subgraph response missing 'data': $response")
    }

    companion object {
        // Shared selection set for [ORDER_BY_ID_QUERY] and [USER_ORDERS_QUERY] — keeps the two
        // entry points byte-aligned on the fields the snapshot parser expects.
        private const val ORDER_FIELDS = """
                orderId
                type
                status
                circleId
                userAddress
                usdcRecipientAddress
                usdcAmount
                fiatAmount
                currency
                placedAt
                acceptedAt
                paidAt
                completedAt
                cancelledAt
                acceptedMerchantAddress
                pubkey
                encUpi
                encMerchantUpi
                actualUsdcAmount
                actualFiatAmount
                blockNumber
                blockTimestamp
                transactionHash"""

        const val ORDER_BY_ID_QUERY = """
            query OrderById(${'$'}orderId: BigInt!) {
              orders_collection(where: { orderId: ${'$'}orderId }) {$ORDER_FIELDS
              }
            }
        """

        // Mirrors user-app-client's ORDERS_COLLECTION_WITH_DATE_FILTER_QUERY but without a date
        // window: this drives the all-time history list shown in the P2P transactions screen.
        //
        // Matches on recipient as well as placer. An onramp BUY is placed on-chain by the operator
        // account, so `userAddress` is the operator's and only `usdcRecipientAddress` is the user's
        // — filtering on the placer alone hides every order the user bought.
        const val USER_ORDERS_QUERY = """
            query UserOrders(${'$'}userAddress: String!, ${'$'}first: Int!, ${'$'}skip: Int!) {
              orders_collection(
                where: {
                  or: [
                    { userAddress: ${'$'}userAddress }
                    { usdcRecipientAddress: ${'$'}userAddress }
                  ]
                }
                first: ${'$'}first
                skip: ${'$'}skip
                orderBy: placedAt
                orderDirection: desc
              ) {$ORDER_FIELDS
              }
            }
        """

        // Mirrors p2pdotme-sdk/src/orders/internal/routing/subgraph/queries.ts
        const val CIRCLES_FOR_ROUTING_QUERY = """
            query CirclesForRouting(${'$'}currency: Bytes!) {
              circles(
                first: 1000
                where: {
                  currency: ${'$'}currency
                  metrics_: {
                    circleStatus_in: ["active", "bootstrap", "paused"]
                  }
                }
              ) {
                circleId
                currency
                metrics {
                  circleScore
                  circleStatus
                  scoreState {
                    activeMerchantsCount
                  }
                }
              }
            }
        """
    }
}
