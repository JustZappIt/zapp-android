// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.util

private const val WORD = 32

/** Left-pads (or left-truncates) this byte array to one canonical 32-byte EVM word. */
fun ByteArray.padLeftToWord(): ByteArray =
    when {
        size == WORD -> this
        size > WORD -> copyOfRange(size - WORD, size)
        else -> ByteArray(WORD).also { copyInto(it, destinationOffset = WORD - size) }
    }
