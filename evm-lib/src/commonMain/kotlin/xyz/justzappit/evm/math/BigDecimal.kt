// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

@file:Suppress("TooManyFunctions") // The expect API intentionally keeps all decimal operations in one parity surface.

package xyz.justzappit.evm.math

expect class BigDecimal : Comparable<BigDecimal> {
    constructor(value: String)

    fun signum(): Int

    override operator fun compareTo(other: BigDecimal): Int
}

enum class DecimalRounding {
    HALF_UP,
    DOWN,
    UP,
}

expect fun bigDecimalFromBigInteger(value: BigInteger): BigDecimal

expect fun decimalMultiply(left: BigDecimal, right: BigDecimal): BigDecimal

expect fun decimalSubtract(left: BigDecimal, right: BigDecimal): BigDecimal

expect fun decimalDivide(
    dividend: BigDecimal,
    divisor: BigDecimal,
    scale: Int,
    rounding: DecimalRounding,
): BigDecimal

expect fun decimalSetScale(value: BigDecimal, scale: Int, rounding: DecimalRounding): BigDecimal

expect fun decimalMovePointLeft(value: BigDecimal, distance: Int): BigDecimal

expect fun decimalMovePointRight(value: BigDecimal, distance: Int): BigDecimal

expect fun decimalToBigInteger(value: BigDecimal): BigInteger

expect fun decimalToLong(value: BigDecimal): Long

expect fun decimalStripTrailingZeros(value: BigDecimal): BigDecimal

expect fun decimalToPlainString(value: BigDecimal): String
