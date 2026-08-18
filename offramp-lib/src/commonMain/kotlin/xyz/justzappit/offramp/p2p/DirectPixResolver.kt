// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.p2p

import io.ktor.client.HttpClient
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable

/**
 * Resolves dynamic-PIX amounts by fetching the issuing bank's `location` endpoint DIRECTLY from the
 * device. Native HTTP has no CORS restriction (unlike the browser the official web app runs in), so
 * no server-side forwarding proxy is required. The resolver fetches the location, authenticates the
 * returned JWS through the issuer's same-host JWKS and platform certificate trust, and only then
 * reads `valor.original`.
 */
class DirectPixResolver private constructor(
    private val httpClient: HttpClient,
    private val jwsVerifier: PixJwsVerifier,
) : DynamicPixResolver {
    constructor(httpClient: HttpClient) : this(httpClient, PixJwsVerifier(httpClient))

    override suspend fun resolveAmount(locationUrl: String, orderId: String?): String? {
        val requestedUrl = requireSafePixUrl(locationUrl)
        val response = httpClient.get(requestedUrl) { accept(ContentType.Any) }
        if (!response.status.isSuccess()) {
            throw PixFetchException("PIX location endpoint responded ${response.status}")
        }
        // Ktor may follow redirects. Revalidate the final target so an apparently public QR endpoint
        // cannot redirect the wallet into localhost, a private LAN, or a cloud metadata service.
        val finalIssuerUrl =
            requireSafePixUrl(
                response.call.request.url
                    .toString()
            )
        val declaredSize =
            response.headers[HttpHeaders.ContentLength]
                ?.toLongOrNull()
        if (declaredSize != null && declaredSize > MAX_PIX_RESPONSE_BYTES) {
            throw PixFetchException("PIX response exceeds $MAX_PIX_RESPONSE_BYTES bytes")
        }
        return jwsVerifier.verifyAndExtractAmount(readBoundedBody(response.bodyAsChannel()), finalIssuerUrl)
    }

    internal companion object {
        fun withVerifier(httpClient: HttpClient, verifier: PixJwsVerifier) = DirectPixResolver(httpClient, verifier)
    }
}

internal suspend fun readBoundedBody(channel: io.ktor.utils.io.ByteReadChannel): String {
    val bytes = ByteArray(MAX_PIX_RESPONSE_BYTES + 1)
    var total = 0
    while (total < bytes.size) {
        val count = channel.readAvailable(bytes, total, bytes.size - total)
        if (count == -1) break
        total += count
    }
    if (total > MAX_PIX_RESPONSE_BYTES) {
        throw PixFetchException("PIX response exceeds $MAX_PIX_RESPONSE_BYTES bytes")
    }
    return bytes.decodeToString(endIndex = total)
}

internal fun requireSafePixUrl(raw: String): String {
    val url = runCatching { Url(raw) }.getOrElse { throw PixFetchException("invalid PIX location URL") }
    if (url.protocol.name != "https") throw PixFetchException("PIX location must use HTTPS")
    if (url.port != HTTPS_PORT) throw PixFetchException("PIX location must use the standard HTTPS port")
    val host =
        url.host
            .trimEnd('.')
            .removePrefix("[")
            .removeSuffix("]")
            .lowercase()
    if (host.isEmpty() || isForbiddenPixHost(host)) {
        throw PixFetchException("PIX location resolves to a forbidden host")
    }
    return url.toString()
}

private fun isForbiddenPixHost(host: String): Boolean {
    if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".internal")) {
        return true
    }
    if (host == "::1" ||
        host == "0:0:0:0:0:0:0:1" ||
        (':' in host && (host.startsWith("fe80:") || host.startsWith("fc") || host.startsWith("fd")))
    ) {
        return true
    }
    val octets = host.split('.').map { it.toIntOrNull() }
    if (octets.size != IPV4_OCTETS || octets.any { it == null || it !in 0..255 }) return false
    val a = checkNotNull(octets[0])
    val b = checkNotNull(octets[1])
    return a == 0 ||
        a == 10 ||
        a == 127 ||
        a >= 224 ||
        (a == 100 && b in 64..127) ||
        (a == 169 && b == 254) ||
        (a == 172 && b in 16..31) ||
        (a == 192 && b == 168) ||
        (a == 198 && b in 18..19)
}

private const val HTTPS_PORT = 443
private const val IPV4_OCTETS = 4
internal const val MAX_PIX_RESPONSE_BYTES = 64 * 1024
