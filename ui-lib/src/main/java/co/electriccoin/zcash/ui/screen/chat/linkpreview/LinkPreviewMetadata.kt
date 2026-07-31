// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.linkpreview

import java.net.URI
import java.util.Locale

data class LinkPreviewMetadata(
    val url: String,
    val title: String?,
    val description: String?,
    val siteName: String,
    val imageUrl: String?,
    val imageData: ByteArray? = null,
)

private val WEB_URL_REGEX = Regex("https?://[^\\s<>]+", RegexOption.IGNORE_CASE)
private val META_TAG_REGEX = Regex("<meta\\b[^>]*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val TITLE_REGEX = Regex("<title\\b[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val ATTRIBUTE_REGEX =
    Regex("([:\\w-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'=<>`]+))", RegexOption.IGNORE_CASE)
private val ENTITY_REGEX = Regex("&(#x[0-9a-f]+|#[0-9]+|amp|quot|apos|lt|gt|nbsp);", RegexOption.IGNORE_CASE)
private val WHITESPACE_REGEX = Regex("\\s+")

internal data class DetectedWebUrl(
    val url: String,
    val start: Int,
    val endExclusive: Int,
)

internal fun detectWebUrls(text: String): List<DetectedWebUrl> =
    WEB_URL_REGEX
        .findAll(text)
        .mapNotNull { match ->
            val url = match.value.trimEnd('.', ',', '!', '?', ';', ':', ')', ']', '}')
            val uri = runCatching { URI(url) }.getOrNull()
            val scheme = uri?.scheme?.lowercase(Locale.ROOT)
            url
                .takeIf { uri?.host != null && scheme in setOf("http", "https") }
                ?.let { DetectedWebUrl(it, match.range.first, match.range.first + it.length) }
        }.toList()

internal fun firstWebUrl(text: String): String? = detectWebUrls(text).firstNotNullOfOrNull { safePreviewUrl(it.url) }

internal fun parseLinkPreview(
    requestedUrl: String,
    html: String,
): LinkPreviewMetadata? {
    val safeUrl = safePreviewUrl(requestedUrl) ?: return null
    val metadata = linkedMapOf<String, String>()
    META_TAG_REGEX.findAll(html).forEach { match ->
        val attributes = parseAttributes(match.value)
        val key = (attributes["property"] ?: attributes["name"])?.lowercase(Locale.ROOT)
        val content = attributes["content"]?.cleanHtmlValue()
        if (!key.isNullOrBlank() && !content.isNullOrBlank() && key !in metadata) {
            metadata[key] = content
        }
    }

    val title =
        metadata["og:title"]
            ?: metadata["twitter:title"]
            ?: TITLE_REGEX
                .find(html)
                ?.groupValues
                ?.get(1)
                ?.cleanHtmlValue()
    val description = metadata["og:description"] ?: metadata["twitter:description"] ?: metadata["description"]
    val image =
        (metadata["og:image:secure_url"] ?: metadata["og:image"] ?: metadata["twitter:image"])
            ?.let { resolveSafeUrl(safeUrl, it) }
    if (title.isNullOrBlank() && description.isNullOrBlank() && image == null) return null

    val siteName =
        metadata["og:site_name"]
            ?.takeIf { it.isNotBlank() }
            ?: checkNotNull(URI(safeUrl).host).removePrefix("www.")
    return LinkPreviewMetadata(
        url = safeUrl,
        title = title?.take(MAX_TITLE_LENGTH),
        description = description?.take(MAX_DESCRIPTION_LENGTH),
        siteName = siteName.take(MAX_SITE_NAME_LENGTH),
        imageUrl = image,
    )
}

internal fun safePreviewUrl(rawUrl: String): String? =
    runCatching {
        val uri = URI(rawUrl.trim())
        val host =
            uri.host
                ?.trimEnd('.')
                ?.removePrefix("[")
                ?.removeSuffix("]")
                ?.lowercase(Locale.ROOT)
                ?: return null
        if (uri.scheme?.lowercase(Locale.ROOT) != "https" || uri.userInfo != null) return null
        if (uri.port != -1 && uri.port != HTTPS_PORT) return null
        if (host.isBlank() || isForbiddenHost(host)) return null
        uri.toASCIIString()
    }.getOrNull()

private fun resolveSafeUrl(baseUrl: String, rawUrl: String): String? =
    runCatching { URI(baseUrl).resolve(rawUrl.trim()).toString() }
        .getOrNull()
        ?.let(::safePreviewUrl)

private fun parseAttributes(tag: String): Map<String, String> =
    ATTRIBUTE_REGEX
        .findAll(tag)
        .associate { match ->
            val value =
                match.groupValues
                    .drop(2)
                    .firstOrNull { it.isNotEmpty() }
                    .orEmpty()
            match.groupValues[1].lowercase(Locale.ROOT) to value
        }

private fun String.cleanHtmlValue(): String =
    ENTITY_REGEX
        .replace(this) { match -> decodeEntity(match.groupValues[1]) }
        .replace(WHITESPACE_REGEX, " ")
        .trim()

private fun decodeEntity(entity: String): String =
    when (entity.lowercase(Locale.ROOT)) {
        "amp" -> "&"
        "quot" -> "\""
        "apos" -> "'"
        "lt" -> "<"
        "gt" -> ">"
        "nbsp" -> " "
        else -> decodeNumericEntity(entity)
    }

private fun decodeNumericEntity(entity: String): String {
    val codePoint =
        if (entity.startsWith("#x", ignoreCase = true)) {
            entity.drop(2).toIntOrNull(16)
        } else {
            entity.drop(1).toIntOrNull()
        }
    return codePoint?.takeIf(Character::isValidCodePoint)?.let(Character::toChars)?.concatToString() ?: "&$entity;"
}

private fun isForbiddenHost(host: String): Boolean {
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
    val first = checkNotNull(octets[0])
    val second = checkNotNull(octets[1])
    return first == 0 ||
        first == 10 ||
        first == 127 ||
        first >= 224 ||
        (first == 100 && second in 64..127) ||
        (first == 169 && second == 254) ||
        (first == 172 && second in 16..31) ||
        (first == 192 && second == 168)
}

private const val HTTPS_PORT = 443
private const val IPV4_OCTETS = 4
private const val MAX_TITLE_LENGTH = 200
private const val MAX_DESCRIPTION_LENGTH = 400
private const val MAX_SITE_NAME_LENGTH = 100
