// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.math

expect class BigInteger : Comparable<BigInteger> {
    constructor(value: String)
    constructor(value: String, radix: Int)
    constructor(signum: Int, magnitude: ByteArray)

    fun add(other: BigInteger): BigInteger
    fun subtract(other: BigInteger): BigInteger
    fun multiply(other: BigInteger): BigInteger
    fun divide(other: BigInteger): BigInteger
    fun remainder(other: BigInteger): BigInteger
    fun mod(modulus: BigInteger): BigInteger
    fun modInverse(modulus: BigInteger): BigInteger
    fun negate(): BigInteger
    fun pow(exponent: Int): BigInteger
    fun shiftLeft(distance: Int): BigInteger
    fun shiftRight(distance: Int): BigInteger
    fun signum(): Int
    fun bitLength(): Int
    fun toByteArray(): ByteArray
    fun toInt(): Int
    fun toLong(): Long
    fun toString(radix: Int): String
    override operator fun compareTo(other: BigInteger): Int

}

expect val bigIntegerZero: BigInteger
expect val bigIntegerOne: BigInteger
expect fun bigIntegerValueOf(value: Long): BigInteger

operator fun BigInteger.plus(other: BigInteger): BigInteger = add(other)
operator fun BigInteger.minus(other: BigInteger): BigInteger = subtract(other)
operator fun BigInteger.times(other: BigInteger): BigInteger = multiply(other)
operator fun BigInteger.div(other: BigInteger): BigInteger = divide(other)
operator fun BigInteger.rem(other: BigInteger): BigInteger = remainder(other)
