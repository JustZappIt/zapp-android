// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.p2p

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import xyz.justzappit.evm.math.BigDecimal
import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.math.DecimalRounding
import xyz.justzappit.evm.math.bigDecimalFromBigInteger
import xyz.justzappit.evm.math.bigIntegerValueOf
import xyz.justzappit.evm.math.bigIntegerZero
import xyz.justzappit.evm.math.decimalMovePointLeft
import xyz.justzappit.evm.math.decimalMovePointRight
import xyz.justzappit.evm.math.decimalSetScale
import xyz.justzappit.evm.math.decimalStripTrailingZeros
import xyz.justzappit.evm.math.decimalToBigInteger
import xyz.justzappit.evm.math.decimalToPlainString
import xyz.justzappit.evm.math.minus
import xyz.justzappit.evm.math.plus
import kotlin.jvm.JvmInline

/**
 * A 6-decimal token amount, stored in micro-units (1 USDC = 1_000_000 micro-USDC). Used for both
 * USDC and any fiat amount the p2p.me diamond expresses in the same 6-decimal currency unit (see
 * [PriceConfig]).
 *
 * The point of this wrapper is to make "is this `5_000_000` micros or `5` USDC?" un-confusable at
 * every callsite. Construct via [Usdc6.ofMicros] (raw on-chain integer) or [Usdc6.ofWhole]
 * (decimal). Mixing the two without an explicit conversion is a compile error.
 */
@Serializable(with = Usdc6.Usdc6Serializer::class)
@JvmInline
value class Usdc6(
    val micros: BigInteger
) : Comparable<Usdc6> {
    val whole: BigDecimal get() = decimalMovePointLeft(bigDecimalFromBigInteger(micros), DECIMALS)

    /**
     * Plain decimal display string. When [stripTrailingZeros] is true, `5.000000` renders as `5`
     * (used by row formatters that prefer compact display); otherwise the full 6dp is preserved.
     */
    fun toDisplayString(stripTrailingZeros: Boolean = false): String =
        if (stripTrailingZeros) {
            decimalToPlainString(decimalStripTrailingZeros(whole))
        } else {
            decimalToPlainString(whole)
        }

    /**
     * Display string for a fiat amount this happens to carry. No payment rail can charge the six
     * decimals held here, so every fiat surface must quantise to the currency's own precision or
     * the app shows an amount the user cannot actually send.
     */
    fun toFiatString(currency: CurrencyCode): String =
        decimalToPlainString(decimalSetScale(whole, currency.precision, DecimalRounding.HALF_UP))

    operator fun plus(other: Usdc6): Usdc6 = Usdc6(micros + other.micros)

    operator fun minus(other: Usdc6): Usdc6 = Usdc6(micros - other.micros)

    override fun compareTo(other: Usdc6): Int = micros.compareTo(other.micros)

    override fun toString(): String = "$whole(=${micros}µ)"

    companion object {
        const val DECIMALS: Int = 6

        val ZERO: Usdc6 = Usdc6(bigIntegerZero)

        /** Construct from raw 6-decimal micros (the wire format used by the diamond contract). */
        fun ofMicros(micros: BigInteger): Usdc6 = Usdc6(micros)

        fun ofMicros(micros: Long): Usdc6 = Usdc6(bigIntegerValueOf(micros))

        /**
         * Construct from a whole-token decimal amount (i.e. 5.50 USDC). Rounds half-up at the
         * sixth decimal place; inputs with more than 6 decimals round (not truncate) to the
         * nearest micro.
         */
        fun ofWhole(whole: BigDecimal): Usdc6 =
            Usdc6(
                decimalToBigInteger(
                    decimalSetScale(
                        decimalMovePointRight(whole, DECIMALS),
                        0,
                        DecimalRounding.HALF_UP,
                    ),
                ),
            )
    }

    object Usdc6Serializer : KSerializer<Usdc6> {
        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("xyz.justzappit.offramp.p2p.Usdc6", PrimitiveKind.STRING)

        override fun deserialize(decoder: Decoder): Usdc6 = Usdc6(BigInteger(decoder.decodeString()))

        override fun serialize(encoder: Encoder, value: Usdc6) = encoder.encodeString(value.micros.toString())
    }
}
