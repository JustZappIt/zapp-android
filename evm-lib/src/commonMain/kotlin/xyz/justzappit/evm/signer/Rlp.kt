// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.signer

import kotlin.jvm.JvmInline
import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.math.bigIntegerValueOf

sealed interface RlpItem {
    @JvmInline
    value class Bytes(
        val value: ByteArray
    ) : RlpItem

    @JvmInline
    value class L(
        val items: List<RlpItem>
    ) : RlpItem
}

fun rlpBytes(b: ByteArray): RlpItem = RlpItem.Bytes(b)

fun rlpEmpty(): RlpItem = RlpItem.Bytes(ByteArray(0))

fun rlpInt(v: BigInteger): RlpItem {
    if (v.signum() == 0) return rlpEmpty()
    require(v.signum() > 0) { "RLP integers must be non-negative" }
    return RlpItem.Bytes(stripLeadingZeros(v.toByteArray()))
}

fun rlpInt(v: Long): RlpItem = rlpInt(bigIntegerValueOf(v))

fun rlpList(vararg items: RlpItem): RlpItem = RlpItem.L(items.toList())

fun rlpList(items: List<RlpItem>): RlpItem = RlpItem.L(items)

object Rlp {
    fun encode(item: RlpItem): ByteArray =
        when (item) {
            is RlpItem.Bytes -> encodeBytes(item.value)
            is RlpItem.L -> encodeList(item.items)
        }

    private fun encodeBytes(bytes: ByteArray): ByteArray =
        if (bytes.size == 1 && (bytes[0].toInt() and BYTE_MASK) < SINGLE_BYTE_LIMIT) {
            bytes.copyOf()
        } else {
            encodeLength(bytes.size, STRING_OFFSET) + bytes
        }

    private fun encodeList(items: List<RlpItem>): ByteArray {
        val encodedItems = items.map(::encode)
        val inner = ByteArray(encodedItems.sumOf(ByteArray::size))
        var offset = 0
        encodedItems.forEach { bytes ->
            bytes.copyInto(inner, offset)
            offset += bytes.size
        }
        return encodeLength(inner.size, LIST_OFFSET) + inner
    }

    private fun encodeLength(length: Int, offset: Int): ByteArray =
        if (length < SHORT_LENGTH_THRESHOLD) {
            byteArrayOf((offset + length).toByte())
        } else {
            val lenBytes = stripLeadingZeros(bigIntegerValueOf(length.toLong()).toByteArray())
            byteArrayOf((offset + SHORT_LENGTH_THRESHOLD + lenBytes.size - 1).toByte()) + lenBytes
        }

    private const val SHORT_LENGTH_THRESHOLD = 56
    private const val SINGLE_BYTE_LIMIT = 0x80
    private const val STRING_OFFSET = 0x80
    private const val LIST_OFFSET = 0xc0
    private const val BYTE_MASK = 0xff
}

private fun stripLeadingZeros(b: ByteArray): ByteArray {
    var i = 0
    while (i < b.size - 1 && b[i] == 0.toByte()) i++
    return if (i == 0) b else b.copyOfRange(i, b.size)
}
