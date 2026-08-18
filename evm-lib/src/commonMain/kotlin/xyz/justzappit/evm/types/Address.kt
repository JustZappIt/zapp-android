// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.types

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import xyz.justzappit.evm.abi.keccak256
import xyz.justzappit.evm.util.hexToBytes
import xyz.justzappit.evm.util.toHex

@Serializable(with = Address.AddressSerializer::class)
class Address private constructor(
    val checksumHex: String
) {
    val bytes: ByteArray get() = checksumHex.substring(PREFIX.length).hexToBytes()
    val lowercaseHex: String get() = PREFIX + checksumHex.substring(PREFIX.length).lowercase()

    override fun toString(): String = checksumHex

    override fun equals(other: Any?): Boolean =
        this === other || (other is Address && checksumHex.equals(other.checksumHex, ignoreCase = true))

    override fun hashCode(): Int = lowercaseHex.hashCode()

    companion object {
        const val LEN_BYTES = 20
        const val HEX_LEN = LEN_BYTES * 2
        const val PREFIX = "0x"

        fun parse(input: String): Address =
            parseOrNull(input) ?: throw IllegalArgumentException("Not a valid EVM address: '$input'")

        fun parseOrNull(input: String): Address? {
            val raw =
                (if (input.startsWith(PREFIX) || input.startsWith("0X")) input.substring(2) else input)
                    .takeIf { it.length == HEX_LEN } ?: return null
            if (!raw.all { it in HEX_CHARS }) return null
            return Address(PREFIX + toEip55(raw.lowercase()))
        }

        fun fromBytes(bytes: ByteArray): Address {
            require(bytes.size == LEN_BYTES) { "Address must be 20 bytes, got ${bytes.size}" }
            return Address(PREFIX + toEip55(bytes.toHex()))
        }

        internal fun toEip55(lowerHex: String): String {
            val hashHex = keccak256(lowerHex.encodeToByteArray()).toHex()
            return buildString(lowerHex.length) {
                for (i in lowerHex.indices) {
                    val char = lowerHex[i]
                    if (char.isDigit()) {
                        append(char)
                    } else {
                        val nibble = hashHex[i].digitToInt(HEX_RADIX)
                        append(if (nibble >= EIP55_THRESHOLD) char.uppercaseChar() else char)
                    }
                }
            }
        }

        private const val EIP55_THRESHOLD = 8
        private const val HEX_RADIX = 16
        private val HEX_CHARS: Set<Char> = ('0'..'9').toSet() + ('a'..'f').toSet() + ('A'..'F').toSet()

        val ZERO: Address = parse("0x0000000000000000000000000000000000000000")
    }

    object AddressSerializer : KSerializer<Address> {
        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("xyz.justzappit.evm.types.Address", PrimitiveKind.STRING)

        override fun deserialize(decoder: Decoder): Address = parse(decoder.decodeString())

        override fun serialize(encoder: Encoder, value: Address) = encoder.encodeString(value.checksumHex)
    }
}
