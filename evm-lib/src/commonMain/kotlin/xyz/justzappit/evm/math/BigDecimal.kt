// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.math

expect class BigDecimal : Comparable<BigDecimal> {
    constructor(value: String)

    fun signum(): Int

    override operator fun compareTo(other: BigDecimal): Int
}

enum class DecimalRounding {
    HALF_UP,
    DOWN,
}

expect fun bigDecimalFromBigInteger(value: BigInteger): BigDecimal

expect fun decimalMultiply(left: BigDecimal, right: BigDecimal): BigDecimal

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
