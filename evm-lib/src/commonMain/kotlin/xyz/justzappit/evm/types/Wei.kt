// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.types

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.jvm.JvmInline
import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.math.bigIntegerValueOf
import xyz.justzappit.evm.math.bigIntegerZero
import xyz.justzappit.evm.math.plus
import xyz.justzappit.evm.math.times

/**
 * Atomic wei amount — i.e. ETH * 10^18 on Ethereum / Base / any EVM chain. Distinguished from
 * gas-unit counts (`gasLimit`) and nonces by type so that fee math at the signer layer can't
 * accidentally mix the three.
 */
@Serializable(with = Wei.WeiSerializer::class)
@JvmInline
value class Wei(
    val value: BigInteger
) {
    init {
        require(value.signum() >= 0) { "Wei must be non-negative, got $value" }
    }

    operator fun plus(other: Wei): Wei = Wei(value + other.value)

    operator fun times(scalar: Int): Wei = Wei(value * bigIntegerValueOf(scalar.toLong()))

    override fun toString(): String = "${value}wei"

    companion object {
        val ZERO: Wei = Wei(bigIntegerZero)

        fun ofLong(value: Long): Wei = Wei(bigIntegerValueOf(value))
    }

    object WeiSerializer : KSerializer<Wei> {
        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("xyz.justzappit.evm.types.Wei", PrimitiveKind.STRING)

        override fun deserialize(decoder: Decoder): Wei = Wei(BigInteger(decoder.decodeString()))

        override fun serialize(encoder: Encoder, value: Wei) = encoder.encodeString(value.value.toString())
    }
}
