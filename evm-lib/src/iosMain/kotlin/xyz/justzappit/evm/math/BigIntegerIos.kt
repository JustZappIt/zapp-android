// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.math

import com.ionspin.kotlin.bignum.integer.Sign
import com.ionspin.kotlin.bignum.integer.BigInteger as KmpBigInteger

actual class BigInteger internal constructor(
    internal val value: KmpBigInteger
) : Comparable<BigInteger> {
    actual constructor(value: String) : this(KmpBigInteger.parseString(value, DECIMAL_RADIX))
    actual constructor(value: String, radix: Int) : this(KmpBigInteger.parseString(value, radix))
    actual constructor(signum: Int, magnitude: ByteArray) : this(
        KmpBigInteger.fromByteArray(
            magnitude,
            when {
                signum < 0 -> Sign.NEGATIVE
                signum > 0 -> Sign.POSITIVE
                else -> Sign.ZERO
            },
        ),
    )

    actual fun add(other: BigInteger): BigInteger = BigInteger(value + other.value)

    actual fun subtract(other: BigInteger): BigInteger = BigInteger(value - other.value)

    actual fun multiply(other: BigInteger): BigInteger = BigInteger(value * other.value)

    actual fun divide(other: BigInteger): BigInteger = BigInteger(value / other.value)

    actual fun remainder(other: BigInteger): BigInteger = BigInteger(value % other.value)

    actual fun mod(modulus: BigInteger): BigInteger = BigInteger(value.mod(modulus.value))

    actual fun modInverse(modulus: BigInteger): BigInteger = BigInteger(value.modInverse(modulus.value))

    actual fun negate(): BigInteger = BigInteger(-value)

    actual fun pow(exponent: Int): BigInteger = BigInteger(value.pow(exponent))

    actual fun shiftLeft(distance: Int): BigInteger = BigInteger(value shl distance)

    actual fun shiftRight(distance: Int): BigInteger = BigInteger(value shr distance)

    actual fun signum(): Int = value.signum()

    actual fun bitLength(): Int =
        if (value.signum() < 0) {
            (-value - KmpBigInteger.ONE).bitLength()
        } else {
            value.bitLength()
        }

    /** Matches java.math.BigInteger's minimal two's-complement encoding exactly. */
    actual fun toByteArray(): ByteArray {
        if (value == KmpBigInteger.ZERO) return byteArrayOf(0)
        val magnitude = value.toByteArray()
        if (value.signum() > 0) {
            return if (magnitude.first().toInt() and SIGN_BIT != 0) byteArrayOf(0) + magnitude else magnitude
        }
        val twosComplement = magnitude.copyOf()
        var carry = 1
        for (index in twosComplement.lastIndex downTo 0) {
            val sum = (twosComplement[index].toInt() xor BYTE_MASK) + carry
            twosComplement[index] = sum.toByte()
            carry = sum ushr BYTE_BITS
        }
        return if (twosComplement.first().toInt() and SIGN_BIT == 0) {
            byteArrayOf((-1).toByte()) + twosComplement
        } else {
            twosComplement
        }
    }

    actual fun toInt(): Int = value.intValue(exactRequired = false)

    actual fun toLong(): Long = value.longValue(exactRequired = false)

    actual fun toString(radix: Int): String = value.toString(radix)

    actual override fun compareTo(other: BigInteger): Int = value.compareTo(other.value)

    override fun equals(other: Any?): Boolean = other is BigInteger && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value.toString()
}

actual val bigIntegerZero: BigInteger = BigInteger("0")
actual val bigIntegerOne: BigInteger = BigInteger("1")

actual fun bigIntegerValueOf(value: Long): BigInteger = BigInteger(value.toString())

private const val DECIMAL_RADIX = 10
private const val SIGN_BIT = 0x80
private const val BYTE_MASK = 0xFF
private const val BYTE_BITS = 8
