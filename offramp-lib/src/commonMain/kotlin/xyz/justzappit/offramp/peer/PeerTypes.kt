// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.peer

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import xyz.justzappit.evm.math.BigDecimal
import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.math.bigDecimalFromBigInteger
import xyz.justzappit.evm.math.decimalMovePointLeft
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.util.hexToBytes
import xyz.justzappit.evm.util.toHex
import kotlin.jvm.JvmInline

/**
 * The protocol traffics in three different bytes32 hashes that are indistinguishable as strings.
 * Putting each in its own type is what stops a payee hash reaching a payment-method slot, which
 * would escrow funds against a deposit no buyer can fill.
 */
@JvmInline
value class PayeeHash private constructor(
    val hex: String
) {
    val bytes: ByteArray get() = hex.hexToBytes()

    companion object {
        fun parse(raw: String): PayeeHash = PayeeHash(normalizeBytes32(raw, "payee hash"))

        fun parseOrNull(raw: String): PayeeHash? = runCatching { parse(raw) }.getOrNull()
    }
}

@JvmInline
value class PaymentMethodHash private constructor(
    val hex: String
) {
    val bytes: ByteArray get() = hex.hexToBytes()

    companion object {
        fun parse(raw: String): PaymentMethodHash = PaymentMethodHash(normalizeBytes32(raw, "payment method hash"))
    }
}

@JvmInline
value class CurrencyHash private constructor(
    val hex: String
) {
    val bytes: ByteArray get() = hex.hexToBytes()

    companion object {
        fun parse(raw: String): CurrencyHash = CurrencyHash(normalizeBytes32(raw, "currency hash"))
    }
}

/**
 * One cash-out attempt, named locally the moment the user commits and stable through to settlement.
 *
 * Everything an attempt owns hangs off this: its checkpoint, its running collection, and the screen
 * that watches it. Without it two attempts share one anonymous storage slot, and the second inherits
 * the first's transaction hashes.
 */
@Serializable(with = PeerCashOutId.Serializer::class)
@JvmInline
value class PeerCashOutId private constructor(
    val value: String
) {
    override fun toString(): String = value

    companion object {
        const val SIZE_BYTES: Int = 16

        private const val LENGTH = SIZE_BYTES * 2

        fun of(raw: String): PeerCashOutId {
            val normalized = raw.removePrefix("0x").removePrefix("0X").lowercase()
            require(normalized.length == LENGTH) { "cash-out id must be $SIZE_BYTES bytes" }
            require(normalized.all { it.isHexDigit() }) { "cash-out id is not hex" }
            return PeerCashOutId(normalized)
        }

        fun ofOrNull(raw: String): PeerCashOutId? = runCatching { of(raw) }.getOrNull()

        fun of(bytes: ByteArray): PeerCashOutId {
            require(bytes.size == SIZE_BYTES) { "cash-out id must be $SIZE_BYTES bytes" }
            return PeerCashOutId(bytes.toHex())
        }
    }

    internal object Serializer : KSerializer<PeerCashOutId> {
        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("xyz.justzappit.offramp.peer.PeerCashOutId", PrimitiveKind.STRING)

        override fun deserialize(decoder: Decoder): PeerCashOutId = of(decoder.decodeString())

        override fun serialize(encoder: Encoder, value: PeerCashOutId) = encoder.encodeString(value.value)
    }
}

/** A payee handle that has already been through [PeerPlatform.normalizeHandle]. */
@JvmInline
value class PayeeHandle private constructor(
    val value: String
) {
    override fun toString(): String = REDACTED

    companion object {
        const val REDACTED = "PayeeHandle(redacted)"

        internal fun ofNormalized(value: String): PayeeHandle {
            require(value.isNotBlank()) { "payee handle must not be blank" }
            return PayeeHandle(value)
        }
    }
}

/** A conversion rate at the protocol's 18-decimal fixed point. Never a [Double] near money. */
@JvmInline
value class Rate1e18(
    val raw: BigInteger
) {
    val decimal: BigDecimal get() = decimalMovePointLeft(bigDecimalFromBigInteger(raw), DECIMALS)

    companion object {
        const val DECIMALS: Int = 18

        fun parse(raw: String): Rate1e18 = Rate1e18(BigInteger(raw))
    }
}

@JvmInline
value class Bps(
    val value: Int
) {
    init {
        require(value in 0..MAX) { "bps out of range: $value" }
    }

    companion object {
        const val MAX: Int = 10_000
        val ZERO: Bps = Bps(0)
    }
}

/**
 * Identifies a deposit across escrow deployments. The indexer keys on the [composite] form, the
 * contract on [onchain] alone, and conflating the two is how a staging id reads a production order.
 */
@Serializable
data class PeerDepositId(
    val escrowHex: String,
    val onchain: String,
) {
    init {
        require(Address.parseOrNull(escrowHex) != null) { "deposit id has a malformed escrow address" }
        require(onchain.isNotEmpty() && onchain.all { it.isDigit() }) { "deposit id must be decimal digits" }
    }

    val escrow: Address get() = Address.parse(escrowHex)

    val onchainValue: BigInteger get() = BigInteger(onchain)

    val composite: String get() = escrow.lowercaseHex + SEPARATOR + onchain

    companion object {
        const val SEPARATOR = "_"

        fun of(escrow: Address, onchain: BigInteger): PeerDepositId =
            PeerDepositId(escrowHex = escrow.lowercaseHex, onchain = onchain.toString())

        fun parseOrNull(composite: String): PeerDepositId? {
            val parts = composite.split(SEPARATOR)
            if (parts.size != COMPOSITE_PARTS) return null
            return runCatching { PeerDepositId(escrowHex = parts[0], onchain = parts[1]) }.getOrNull()
        }

        private const val COMPOSITE_PARTS = 2
    }
}

private const val BYTES32_HEX_LENGTH = 64

private fun normalizeBytes32(raw: String, label: String): String {
    val body = raw.removePrefix("0x").removePrefix("0X")
    require(body.length == BYTES32_HEX_LENGTH) { "$label must be 32 bytes" }
    require(body.all { it.isHexDigit() }) { "$label is not hex" }
    return "0x" + body.lowercase()
}

private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
