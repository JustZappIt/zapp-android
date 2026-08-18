// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.math

import java.math.RoundingMode

actual typealias BigDecimal = java.math.BigDecimal

actual fun bigDecimalFromBigInteger(value: BigInteger): BigDecimal = BigDecimal(value)

actual fun decimalMultiply(left: BigDecimal, right: BigDecimal): BigDecimal = left.multiply(right)

actual fun decimalDivide(
    dividend: BigDecimal,
    divisor: BigDecimal,
    scale: Int,
    rounding: DecimalRounding,
): BigDecimal = dividend.divide(divisor, scale, rounding.toJvm())

actual fun decimalSetScale(value: BigDecimal, scale: Int, rounding: DecimalRounding): BigDecimal =
    value.setScale(scale, rounding.toJvm())

actual fun decimalMovePointLeft(value: BigDecimal, distance: Int): BigDecimal = value.movePointLeft(distance)

actual fun decimalMovePointRight(value: BigDecimal, distance: Int): BigDecimal = value.movePointRight(distance)

actual fun decimalToBigInteger(value: BigDecimal): BigInteger = value.toBigInteger()

actual fun decimalToLong(value: BigDecimal): Long = value.toLong()

actual fun decimalStripTrailingZeros(value: BigDecimal): BigDecimal = value.stripTrailingZeros()

actual fun decimalToPlainString(value: BigDecimal): String = value.toPlainString()

private fun DecimalRounding.toJvm(): RoundingMode =
    when (this) {
        DecimalRounding.HALF_UP -> RoundingMode.HALF_UP
        DecimalRounding.DOWN -> RoundingMode.DOWN
    }
