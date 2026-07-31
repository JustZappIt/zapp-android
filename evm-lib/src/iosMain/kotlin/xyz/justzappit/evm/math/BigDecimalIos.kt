// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.math

import com.ionspin.kotlin.bignum.decimal.BigDecimal as KmpBigDecimal
import com.ionspin.kotlin.bignum.decimal.DecimalMode
import com.ionspin.kotlin.bignum.decimal.RoundingMode

actual class BigDecimal internal constructor(
    internal val value: KmpBigDecimal
) : Comparable<BigDecimal> {
    actual constructor(value: String) : this(KmpBigDecimal.parseString(value))

    actual fun signum(): Int = value.signum()
    actual override fun compareTo(other: BigDecimal): Int = value.compareTo(other.value)

    override fun equals(other: Any?): Boolean = other is BigDecimal && value.compareTo(other.value) == 0
    override fun hashCode(): Int = value.toStringExpanded().hashCode()
    override fun toString(): String = value.toStringExpanded()
}

actual fun bigDecimalFromBigInteger(value: BigInteger): BigDecimal =
    BigDecimal(KmpBigDecimal.fromBigInteger(value.value))

actual fun decimalMultiply(left: BigDecimal, right: BigDecimal): BigDecimal =
    BigDecimal(left.value.multiply(right.value))

actual fun decimalDivide(
    dividend: BigDecimal,
    divisor: BigDecimal,
    scale: Int,
    rounding: DecimalRounding,
): BigDecimal =
    BigDecimal(
        dividend.value
            .divide(
                divisor.value,
                DecimalMode(
                    decimalPrecision = (scale + DIVISION_INTEGER_DIGITS).toLong(),
                    roundingMode = rounding.toIos(),
                ),
            ).roundToDigitPositionAfterDecimalPoint(scale.toLong(), rounding.toIos())
            .scale(scale.toLong()),
    )

actual fun decimalSetScale(value: BigDecimal, scale: Int, rounding: DecimalRounding): BigDecimal =
    BigDecimal(value.value.roundToDigitPositionAfterDecimalPoint(scale.toLong(), rounding.toIos()).scale(scale.toLong()))

actual fun decimalMovePointLeft(value: BigDecimal, distance: Int): BigDecimal =
    BigDecimal(
        value.value
            .moveDecimalPoint(-distance)
            .scale((value.value.javaCompatibleScale() + distance).coerceAtLeast(0)),
    )

actual fun decimalMovePointRight(value: BigDecimal, distance: Int): BigDecimal =
    BigDecimal(
        value.value
            .moveDecimalPoint(distance)
            .scale((value.value.javaCompatibleScale() - distance).coerceAtLeast(0)),
    )

actual fun decimalToBigInteger(value: BigDecimal): BigInteger = BigInteger(value.value.toBigInteger())

actual fun decimalToLong(value: BigDecimal): Long = value.value.longValue(exactRequired = false)

actual fun decimalStripTrailingZeros(value: BigDecimal): BigDecimal = value

actual fun decimalToPlainString(value: BigDecimal): String = value.value.toPlainString()

private fun DecimalRounding.toIos(): RoundingMode =
    when (this) {
        DecimalRounding.HALF_UP -> RoundingMode.ROUND_HALF_AWAY_FROM_ZERO
        DecimalRounding.DOWN -> RoundingMode.TOWARDS_ZERO
    }

private fun KmpBigDecimal.javaCompatibleScale(): Long = if (usingScale) scale else 0

private const val DIVISION_INTEGER_DIGITS = 128
