// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.abi

import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.math.bigIntegerOne
import xyz.justzappit.evm.math.bigIntegerValueOf
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.util.padLeftToWord

sealed interface AbiArg {
    val isDynamic: Boolean

    /**
     * Bytes this arg contributes to its parent's head. One word for everything except a static
     * tuple, which inlines its components there instead of pointing at a tail.
     */
    val headSize: Int get() = WORD

    fun head(): ByteArray

    fun tail(): ByteArray
}

data class AbiUint(
    val value: BigInteger
) : AbiArg {
    init {
        require(value.signum() >= 0) { "uint must be non-negative, got $value" }
        require(value.bitLength() <= MAX_UINT_BITS) { "uint exceeds 256 bits" }
    }

    override val isDynamic = false

    override fun head(): ByteArray = value.toByteArray().padLeftToWord()

    override fun tail(): ByteArray = EMPTY
}

data class AbiInt(
    val value: BigInteger
) : AbiArg {
    init {
        // bitLength() excludes the sign bit, so a signed int256 in [-2^255, 2^255-1] has
        // bitLength <= 255; anything wider would silently wrap under two's complement below.
        require(value.bitLength() <= MAX_INT_SIGNED_BITS) { "int256 out of range, got $value" }
    }

    override val isDynamic = false

    override fun head(): ByteArray {
        if (value.signum() >= 0) return value.toByteArray().padLeftToWord()
        // Two's complement: 2^256 + value
        val twos = bigIntegerOne.shiftLeft(MAX_UINT_BITS).add(value)
        return twos.toByteArray().padLeftToWord()
    }

    override fun tail(): ByteArray = EMPTY
}

data class AbiAddress(
    val address: Address
) : AbiArg {
    override val isDynamic = false

    override fun head(): ByteArray = ByteArray(WORD - ADDRESS_BYTES) + address.bytes

    override fun tail(): ByteArray = EMPTY
}

data class AbiBytes32(
    val value: ByteArray
) : AbiArg {
    init {
        require(value.size == WORD) { "bytes32 must be 32 bytes, got ${value.size}" }
    }

    override val isDynamic = false

    override fun head(): ByteArray = value.copyOf()

    override fun tail(): ByteArray = EMPTY

    override fun equals(other: Any?): Boolean = other is AbiBytes32 && value.contentEquals(other.value)

    override fun hashCode(): Int = value.contentHashCode()
}

data class AbiUint8(
    val value: Int
) : AbiArg {
    init {
        require(value in 0..UINT8_MAX) { "uint8 out of range: $value" }
    }

    override val isDynamic = false

    override fun head(): ByteArray = byteArrayOf(value.toByte()).padLeftToWord()

    override fun tail(): ByteArray = EMPTY
}

data class AbiBool(
    val value: Boolean
) : AbiArg {
    override val isDynamic = false

    override fun head(): ByteArray {
        val out = ByteArray(WORD)
        if (value) out[out.size - 1] = 1
        return out
    }

    override fun tail(): ByteArray = EMPTY
}

data class AbiString(
    val value: String
) : AbiArg {
    val bytes: ByteArray = value.encodeToByteArray()
    override val isDynamic = true

    override fun head(): ByteArray = ByteArray(WORD)

    override fun tail(): ByteArray {
        val out = ByteArray(WORD + padded(bytes.size))
        // Length prefix
        val lenBytes = bigIntegerValueOf(bytes.size.toLong()).toByteArray()
        lenBytes.copyInto(out, WORD - lenBytes.size)
        // Data, right-padded with zeros
        bytes.copyInto(out, WORD)
        return out
    }

    override fun equals(other: Any?): Boolean = other is AbiString && value == other.value

    override fun hashCode(): Int = value.hashCode()
}

data class AbiUintArray(
    val values: List<BigInteger>
) : AbiArg {
    init {
        values.forEach {
            require(it.signum() >= 0) { "uint array element must be non-negative, got $it" }
            require(it.bitLength() <= MAX_UINT_BITS_ARRAY) { "uint array element exceeds 256 bits" }
        }
    }

    override val isDynamic = true

    override fun head(): ByteArray = ByteArray(WORD)

    override fun tail(): ByteArray {
        val out = ByteArray(WORD + values.size * WORD)
        val lenBytes = bigIntegerValueOf(values.size.toLong()).toByteArray()
        lenBytes.copyInto(out, WORD - lenBytes.size)
        values.forEachIndexed { i, v ->
            val padded = v.toByteArray().padLeftToWord()
            padded.copyInto(out, WORD + i * WORD)
        }
        return out
    }
}

data class AbiBytes(
    val value: ByteArray
) : AbiArg {
    override val isDynamic = true

    override fun head(): ByteArray = ByteArray(WORD)

    override fun tail(): ByteArray {
        val out = ByteArray(WORD + padded(value.size))
        val lenBytes = bigIntegerValueOf(value.size.toLong()).toByteArray()
        lenBytes.copyInto(out, WORD - lenBytes.size)
        value.copyInto(out, WORD)
        return out
    }

    override fun equals(other: Any?): Boolean = other is AbiBytes && value.contentEquals(other.value)

    override fun hashCode(): Int = value.contentHashCode()
}

data class AbiInt16(
    val value: Int
) : AbiArg {
    init {
        require(value in INT16_MIN..INT16_MAX) { "int16 out of range: $value" }
    }

    override val isDynamic = false

    override fun head(): ByteArray {
        val out = ByteArray(WORD)
        if (value < 0) out.fill(SIGN_EXTENSION_BYTE)
        out[WORD - 2] = (value shr Byte.SIZE_BITS).toByte()
        out[WORD - 1] = value.toByte()
        return out
    }

    override fun tail(): ByteArray = EMPTY
}

data class AbiUint32(
    val value: Long
) : AbiArg {
    init {
        require(value in 0..UINT32_MAX) { "uint32 out of range: $value" }
    }

    override val isDynamic = false

    override fun head(): ByteArray {
        val out = ByteArray(WORD)
        var remaining = value
        repeat(UINT32_BYTES) { i ->
            out[WORD - 1 - i] = (remaining and BYTE_MASK).toByte()
            remaining = remaining shr Byte.SIZE_BITS
        }
        return out
    }

    override fun tail(): ByteArray = EMPTY
}

/**
 * Solidity struct. A tuple is dynamic when any component is, and the distinction changes where its
 * bytes land: a static tuple contributes its components inline to the parent head, a dynamic one
 * contributes an offset and encodes its whole body in the tail.
 */
data class AbiTuple(
    val components: List<AbiArg>
) : AbiArg {
    override val isDynamic = components.any { it.isDynamic }

    override val headSize: Int = if (isDynamic) WORD else components.sumOf { it.headSize }

    override fun head(): ByteArray = if (isDynamic) ByteArray(WORD) else AbiEncoder.encode(components)

    override fun tail(): ByteArray = if (isDynamic) AbiEncoder.encode(components) else EMPTY
}

/** Variable-length array of any element type, including tuples and further arrays. */
data class AbiArray(
    val items: List<AbiArg>
) : AbiArg {
    override val isDynamic = true

    override fun head(): ByteArray = ByteArray(WORD)

    override fun tail(): ByteArray {
        val body = AbiEncoder.encode(items)
        val out = ByteArray(WORD + body.size)
        val lenBytes = bigIntegerValueOf(items.size.toLong()).toByteArray()
        lenBytes.copyInto(out, WORD - lenBytes.size)
        body.copyInto(out, WORD)
        return out
    }
}

internal fun padded(size: Int): Int = if (size % WORD == 0) size else size + WORD - (size % WORD)

internal const val WORD = 32
private const val MAX_UINT_BITS = 256
private const val MAX_UINT_BITS_ARRAY = 256
private const val MAX_INT_SIGNED_BITS = 255
private const val ADDRESS_BYTES = 20
private const val UINT8_MAX = 255
private const val INT16_MIN = -32_768
private const val INT16_MAX = 32_767
private const val UINT32_MAX = 4_294_967_295L
private const val UINT32_BYTES = 4
private const val BYTE_MASK = 0xFFL
private const val SIGN_EXTENSION_BYTE = 0xFF.toByte()
private val EMPTY = ByteArray(0)
