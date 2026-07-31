// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.p2p

import io.ktor.client.HttpClient
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess

/**
 * Optional compatibility adapter for hosted `/pix` forwarders used by browser integrations. Native
 * Android and iOS use [DirectPixResolver]. Even through a forwarder, the issuer JWS is independently
 * authenticated through its same-host JWKS before its payload is exposed.
 */
class PixProxyClient(
    private val httpClient: HttpClient,
    proxyUrl: String,
) : DynamicPixResolver {
    private val baseUrl = proxyUrl.trimEnd('/')
    private val jwsVerifier = PixJwsVerifier(httpClient)

    override suspend fun resolveAmount(locationUrl: String, orderId: String?): String? {
        val issuerUrl = requireSafePixUrl(locationUrl)
        val response =
            httpClient.get("$baseUrl/pix") {
                url {
                    parameters.append(PARAM_LOCATION_URL, issuerUrl)
                    if (orderId != null) parameters.append(PARAM_ORDER_ID, orderId)
                }
                accept(ContentType.Any)
            }
        if (!response.status.isSuccess()) throw PixFetchException("PIX proxy responded ${response.status}")
        val declaredSize = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (declaredSize != null && declaredSize > MAX_PIX_RESPONSE_BYTES) {
            throw PixFetchException("PIX response exceeds $MAX_PIX_RESPONSE_BYTES bytes")
        }
        return jwsVerifier.verifyAndExtractAmount(readBoundedBody(response.bodyAsChannel()), issuerUrl)
    }

    private companion object {
        const val PARAM_LOCATION_URL = "locationUrl"
        const val PARAM_ORDER_ID = "orderId"
    }
}
