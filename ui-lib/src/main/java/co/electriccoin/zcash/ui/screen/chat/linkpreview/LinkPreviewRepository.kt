// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.linkpreview

import co.electriccoin.zcash.ui.common.provider.HttpClientProvider
import io.ktor.client.HttpClient
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.LinkedHashMap

class LinkPreviewRepository(
    private val httpClientProvider: HttpClientProvider,
) {
    private val cache =
        object : LinkedHashMap<String, LinkPreviewMetadata?>(CACHE_SIZE, CACHE_LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LinkPreviewMetadata?>?): Boolean =
                size > CACHE_SIZE
        }

    suspend fun load(rawUrl: String): LinkPreviewMetadata? {
        val url = safePreviewUrl(rawUrl) ?: return null
        synchronized(cache) {
            if (cache.containsKey(url)) return cache[url]
        }
        val preview = fetch(url)
        synchronized(cache) { cache[url] = preview }
        return preview
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun fetch(url: String): LinkPreviewMetadata? =
        withContext(Dispatchers.IO) {
            try {
                httpClientProvider.create().use { client ->
                    val response =
                        client.get(url) {
                            accept(ContentType.Text.Html)
                            header(HttpHeaders.UserAgent, USER_AGENT)
                        }
                    val finalUrl =
                        safePreviewUrl(
                            response.call.request.url
                                .toString(),
                        ) ?: return@use null
                    val contentType =
                        response.headers[HttpHeaders.ContentType]
                            ?.substringBefore(';')
                            ?.trim()
                            ?.lowercase()
                    if (contentType != null && contentType !in SUPPORTED_CONTENT_TYPES) return@use null
                    val contentLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                    if (contentLength != null && contentLength > MAX_RESPONSE_BYTES) return@use null
                    val html =
                        response
                            .bodyAsChannel()
                            .readBoundedBytes(MAX_RESPONSE_BYTES)
                            ?.decodeToString()
                            ?: return@use null
                    val metadata = parseLinkPreview(finalUrl, html) ?: return@use null
                    val imageData =
                        metadata.imageUrl?.let { imageUrl ->
                            runCatching { client.loadPreviewImage(imageUrl) }.getOrNull()
                        }
                    metadata.copy(imageData = imageData)
                }
            } catch (_: Exception) {
                null
            }
        }

    private suspend fun HttpClient.loadPreviewImage(url: String): ByteArray? {
        val response = get(url) { accept(ContentType.Image.Any) }
        val finalUrl =
            response.call.request.url
                .toString()
        if (safePreviewUrl(finalUrl) == null) return null
        val contentType =
            response.headers[HttpHeaders.ContentType]
                ?.substringBefore(';')
                ?.trim()
                ?.lowercase()
        if (contentType?.startsWith("image/") != true) return null
        val contentLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (contentLength != null && contentLength > MAX_IMAGE_BYTES) return null
        return response.bodyAsChannel().readBoundedBytes(MAX_IMAGE_BYTES)
    }

    private suspend fun ByteReadChannel.readBoundedBytes(limit: Int): ByteArray? {
        val bytes = ByteArray(limit + 1)
        var total = 0
        while (total < bytes.size) {
            val count = readAvailable(bytes, total, bytes.size - total)
            if (count == -1) break
            total += count
        }
        if (total > limit) return null
        return bytes.copyOf(total)
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 256 * 1024
        const val MAX_IMAGE_BYTES = 2 * 1024 * 1024
        const val CACHE_SIZE = 128
        const val CACHE_LOAD_FACTOR = 0.75f
        const val USER_AGENT = "Zapp/Android LinkPreview"
        val SUPPORTED_CONTENT_TYPES = setOf("text/html", "application/xhtml+xml")
    }
}
