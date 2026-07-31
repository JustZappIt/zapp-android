// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.p2p

object EmvQr {
    data class TlvEntry(val tag: String, val value: String)

    fun parseTlv(data: String): List<TlvEntry> {
        val entries = mutableListOf<TlvEntry>()
        var position = 0
        while (true) {
            val entry = parseTlvEntryAt(data, position) ?: break
            entries.add(entry)
            position += TAG_LEN + LEN_LEN + entry.value.length
        }
        return entries
    }

    private fun parseTlvEntryAt(data: String, position: Int): TlvEntry? {
        val valueStart = position + TAG_LEN + LEN_LEN
        if (valueStart > data.length) return null
        val tag = data.substring(position, position + TAG_LEN)
        val lengthString = data.substring(position + TAG_LEN, valueStart)
        val length =
            if (tag.all { it.isAsciiDigit() } && lengthString.all { it.isAsciiDigit() }) {
                lengthString.toInt()
            } else {
                null
            }
        return if (length != null && valueStart + length <= data.length) {
            TlvEntry(tag, data.substring(valueStart, valueStart + length))
        } else {
            null
        }
    }

    fun extractTags(data: String, tags: Set<String>): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (entry in parseTlv(data)) {
            if (entry.tag in tags) result[entry.tag] = entry.value
        }
        return result
    }

    fun calculateCrc16(data: String): String {
        val bytes = (data + CRC_TAG_AND_LEN).encodeToByteArray()
        var result = INIT
        for (byte in bytes) {
            result = result xor ((byte.toInt() and BYTE_MASK) shl BYTE_SHIFT)
            repeat(BITS_PER_BYTE) {
                result = result shl 1
                if (result and CARRY != 0) result = result xor POLY
                result = result and WORD_MASK
            }
        }
        return result.toString(HEX_RADIX).uppercase().padStart(CRC_HEX_LEN, '0')
    }

    fun verifyCrc16(qrData: String): Boolean {
        val crcTagIndex = qrData.lastIndexOf(CRC_TAG_AND_LEN)
        if (qrData.length < MIN_CRC_QR_LEN ||
            crcTagIndex == -1 ||
            crcTagIndex + CRC_TAG_AND_LEN.length + CRC_HEX_LEN != qrData.length
        ) {
            return false
        }
        val providedCrc = qrData.substring(crcTagIndex + CRC_TAG_AND_LEN.length)
        return providedCrc.length == CRC_HEX_LEN &&
            providedCrc.all { it.isHexDigit() } &&
            calculateCrc16(qrData.substring(0, crcTagIndex)) == providedCrc.uppercase()
    }

    private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'
    private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private const val TAG_LEN = 2
    private const val LEN_LEN = 2
    private const val CRC_TAG_AND_LEN = "6304"
    private const val CRC_HEX_LEN = 4
    private const val MIN_CRC_QR_LEN = 8
    private const val HEX_RADIX = 16
    private const val POLY = 0x1021
    private const val INIT = 0xFFFF
    private const val WORD_MASK = 0xFFFF
    private const val CARRY = 0x10000
    private const val BYTE_MASK = 0xFF
    private const val BYTE_SHIFT = 8
    private const val BITS_PER_BYTE = 8
}
