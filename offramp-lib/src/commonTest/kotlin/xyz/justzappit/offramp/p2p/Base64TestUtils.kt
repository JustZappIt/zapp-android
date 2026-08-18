// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.p2p

internal fun ByteArray.encodeBase64(): String =
    buildString((size + 2) / 3 * 4) {
        var index = 0
        while (index < size) {
            val remaining = size - index
            val first = this@encodeBase64[index].toInt() and BYTE_MASK
            val second = if (remaining > 1) this@encodeBase64[index + 1].toInt() and BYTE_MASK else 0
            val third = if (remaining > 2) this@encodeBase64[index + 2].toInt() and BYTE_MASK else 0
            val bits = (first shl 16) or (second shl 8) or third
            append(BASE64_ALPHABET[bits ushr 18 and BASE64_MASK])
            append(BASE64_ALPHABET[bits ushr 12 and BASE64_MASK])
            append(if (remaining > 1) BASE64_ALPHABET[bits ushr 6 and BASE64_MASK] else '=')
            append(if (remaining > 2) BASE64_ALPHABET[bits and BASE64_MASK] else '=')
            index += 3
        }
    }

private const val BYTE_MASK = 0xFF
private const val BASE64_MASK = 0x3F
private const val BASE64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
