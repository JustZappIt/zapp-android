// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.abi

object SolidityErrors {
    /** Selector for the standard `Error(string)` Solidity revert. */
    val ERROR_STRING_SELECTOR: Selector4 = Selector4.fromHex("0x08c379a0")

    /**
     * Decodes the ABI payload of an `Error(string)` revert. Returns `null` if the data does not
     * conform: missing selector, malformed head/length, or non-UTF8 body.
     */
    fun decodeErrorString(revertData: ByteArray): String? {
        if (revertData.size < SELECTOR_LEN + WORD_BYTES + WORD_BYTES) return null
        if (Selector4.fromBytesPrefix(revertData) != ERROR_STRING_SELECTOR) return null

        val payload = revertData.copyOfRange(SELECTOR_LEN, revertData.size)
        // payload layout: [offset (32 bytes)] [length (32 bytes)] [data ...]
        val lengthBytes = payload.copyOfRange(WORD_BYTES, WORD_BYTES * 2)
        val length = lengthBytes.fold(0) { acc, b -> (acc shl BYTE_BITS) or (b.toInt() and BYTE_MASK) }
        if (length <= 0 || length > MAX_STRING_BYTES) return null

        val dataStart = WORD_BYTES * 2
        val dataEnd = dataStart + length
        if (payload.size < dataEnd) return null
        return runCatching {
            payload.copyOfRange(dataStart, dataEnd).decodeToString(throwOnInvalidSequence = true)
        }.getOrNull()
    }

    private const val SELECTOR_LEN = 4
    private const val WORD_BYTES = 32
    private const val BYTE_BITS = 8
    private const val BYTE_MASK = 0xff
    private const val MAX_STRING_BYTES = 1024
}
