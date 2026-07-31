// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.util

private const val HEX_RADIX = 16
private val HEX_DIGITS = "0123456789abcdef".toCharArray()

// Error messages must not echo the input: callers pass private-key hex (Ecies.decryptWithPrivateKey,
// the offramp relay identity), and a malformed persisted key would otherwise land in logged exceptions.
fun String.hexToBytes(): ByteArray {
    val raw = if (startsWith("0x") || startsWith("0X")) substring(2) else this
    require(raw.length % 2 == 0) { "hex input must have even length, got ${raw.length}" }
    val out = ByteArray(raw.length / 2)
    var i = 0
    while (i < raw.length) {
        val hi = raw[i].digitToIntOrNull(HEX_RADIX)
        val lo = raw[i + 1].digitToIntOrNull(HEX_RADIX)
        require(hi != null && lo != null) { "hex input contains non-hex character at index $i" }
        out[i / 2] = ((hi shl 4) + lo).toByte()
        i += 2
    }
    return out
}

fun ByteArray.toHex(): String =
    buildString(size * 2) {
        for (b in this@toHex) {
            val value = b.toInt() and 0xff
            append(HEX_DIGITS[value ushr 4])
            append(HEX_DIGITS[value and 0x0f])
        }
    }
