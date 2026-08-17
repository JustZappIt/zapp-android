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
import xyz.justzappit.evm.util.hexToBytes
import xyz.justzappit.evm.util.toHex

@Serializable(with = TxHash.TxHashSerializer::class)
class TxHash(
    bytes: ByteArray
) {
    val bytes: ByteArray = bytes.copyOf()

    init {
        require(this.bytes.size == LEN) { "TxHash must be $LEN bytes, got ${this.bytes.size}" }
    }

    val hex: String get() = PREFIX + bytes.toHex()

    override fun toString(): String = hex

    override fun equals(other: Any?): Boolean = this === other || (other is TxHash && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = bytes.contentHashCode()

    companion object {
        const val LEN = 32
        const val PREFIX = "0x"

        fun fromHex(hex: String): TxHash {
            val raw = if (hex.startsWith(PREFIX) || hex.startsWith("0X")) hex.substring(2) else hex
            return TxHash(raw.lowercase().hexToBytes())
        }
    }

    object TxHashSerializer : KSerializer<TxHash> {
        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("xyz.justzappit.evm.types.TxHash", PrimitiveKind.STRING)

        override fun deserialize(decoder: Decoder): TxHash = fromHex(decoder.decodeString())

        override fun serialize(encoder: Encoder, value: TxHash) = encoder.encodeString(value.hex)
    }
}
