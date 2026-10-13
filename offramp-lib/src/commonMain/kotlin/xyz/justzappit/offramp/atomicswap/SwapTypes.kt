// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.atomicswap

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import xyz.justzappit.evm.abi.AbiAddress
import xyz.justzappit.evm.abi.AbiBool
import xyz.justzappit.evm.abi.AbiBytes32
import xyz.justzappit.evm.abi.AbiEncoder
import xyz.justzappit.evm.abi.keccak256
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.util.hexToBytes
import xyz.justzappit.evm.util.toHex
import xyz.justzappit.offramp.p2p.Usdc6
import kotlin.jvm.JvmInline

const val SWAP_WORD_BYTES = 32
const val SWAP_SHARE_BYTES = 64
const val SWAP_SIGNATURE_BYTES = 65
const val SWAP_AMOUNT_BITS = 120

/** The soonest `t0` a swap may have by default: ten Zcash confirmations, about 12.5 minutes, and a margin. */
const val MIN_SECONDS_TO_T0 = 25 * 60L

/** How long a signed lock or `ready` holds: under the contract's lock duration, so a signature takes one lock only. */
const val SIGNATURE_TTL_SECONDS = 2 * 60L

/** A share is revealed only under a lock with more than this left, so the reveal lands before it lapses. */
const val REVEAL_MARGIN_SECONDS = 5 * 60L

internal const val MAX_ZATOSHI = 2_100_000_000_000_000L

/** Which way a swap converts: shielded ZEC into private dollars, or back. */
enum class SwapDirection { FORWARD, REVERSE }

/** A swap's id on the contract, `keccak256(abi.encode(maker, userShare))`, written `0x` and lowercase. */
@Serializable(with = SwapId.Serializer::class)
@JvmInline
value class SwapId private constructor(
    val hex: String
) {
    val bytes: ByteArray get() = hex.hexToBytes()

    override fun toString(): String = hex

    companion object {
        fun of(bytes: ByteArray): SwapId {
            require(bytes.size == SWAP_WORD_BYTES) { "a swap id is $SWAP_WORD_BYTES bytes" }
            return SwapId("0x" + bytes.toHex())
        }

        fun parse(hex: String): SwapId = of(fixedHex(hex, SWAP_WORD_BYTES))

        /** The id the contract gives [maker]'s swap whose user share is [userShare]. */
        fun of(
            maker: Address,
            userShare: SwapShare
        ): SwapId = of(keccak256(AbiEncoder.encode(listOf(AbiAddress(maker)) + userShare.words())))
    }

    internal object Serializer : HexSerializer<SwapId>("SwapId", ::parse, SwapId::hex)
}

/** A reverse escrow's id, `keccak256(abi.encode(user, makerShare, true))`, never a forward swap's [SwapId.of]. */
object ReverseSwapId {
    fun of(
        user: Address,
        makerShare: SwapShare
    ): SwapId {
        val words = listOf(AbiAddress(user)) + makerShare.words() + AbiBool(true)
        return SwapId.of(keccak256(AbiEncoder.encode(words)))
    }
}

/** A public share `x ‖ y` on the Pallas curve, 64 bytes, written `0x` and lowercase. */
@Serializable(with = SwapShare.Serializer::class)
@JvmInline
value class SwapShare private constructor(
    val hex: String
) {
    val bytes: ByteArray get() = hex.hexToBytes()

    override fun toString(): String = hex

    companion object {
        fun of(bytes: ByteArray): SwapShare {
            require(bytes.size == SWAP_SHARE_BYTES) { "a share is $SWAP_SHARE_BYTES bytes" }
            return SwapShare("0x" + bytes.toHex())
        }

        fun parse(hex: String): SwapShare = of(fixedHex(hex, SWAP_SHARE_BYTES))
    }

    internal object Serializer : HexSerializer<SwapShare>("SwapShare", ::parse, SwapShare::hex)
}

/** A share as the contract's `uint256[2]`: its x and y words. */
internal fun SwapShare.words(): List<AbiBytes32> {
    val share = bytes
    return listOf(
        AbiBytes32(share.copyOfRange(0, SWAP_WORD_BYTES)),
        AbiBytes32(share.copyOfRange(SWAP_WORD_BYTES, SWAP_SHARE_BYTES)),
    )
}

/** What a Railgun note commits to, 32 bytes, written `0x` and lowercase: the note a payout or refund shields to. */
@Serializable(with = NoteCommitment.Serializer::class)
@JvmInline
value class NoteCommitment private constructor(
    val hex: String
) {
    val bytes: ByteArray get() = hex.hexToBytes()

    override fun toString(): String = hex

    companion object {
        fun of(bytes: ByteArray): NoteCommitment {
            require(bytes.size == SWAP_WORD_BYTES) { "a note commitment is $SWAP_WORD_BYTES bytes" }
            return NoteCommitment("0x" + bytes.toHex())
        }

        fun parse(hex: String): NoteCommitment = of(fixedHex(hex, SWAP_WORD_BYTES))
    }

    internal object Serializer : HexSerializer<NoteCommitment>("NoteCommitment", ::parse, NoteCommitment::hex)
}

/** An address written lowercase, as the swap services send them and swap records keep them. */
object LowercaseAddressSerializer : KSerializer<Address> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("xyz.justzappit.offramp.atomicswap.LowercaseAddress", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Address = Address.parse(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: Address
    ) = encoder.encodeString(value.lowercaseHex)
}

/** A value kept as the hex string it parses from, so what a record writes back is what it read. */
internal open class HexSerializer<T>(
    name: String,
    private val parse: (String) -> T,
    private val hex: (T) -> String,
) : KSerializer<T> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("xyz.justzappit.offramp.atomicswap.$name", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): T = parse(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: T
    ) = encoder.encodeString(hex(value))
}

/** [value]'s bytes: `0x` and exactly [bytes] of hex. */
fun fixedHex(
    value: String,
    bytes: Int
): ByteArray {
    require(value.length == 2 + bytes * 2 && value.startsWith("0x") && value.drop(2).isHex()) {
        "invalid fixed-length hex"
    }
    return value.hexToBytes()
}

internal fun String.isHex(): Boolean = all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

/** Throws unless [amount] fits the contract's `uint120` amounts, and moves something when [positive]. */
internal fun requireSwapAmount(
    amount: Usdc6,
    positive: Boolean
) {
    val least = if (positive) 1 else 0
    require(amount.micros.signum() >= least && amount.micros.bitLength() <= SWAP_AMOUNT_BITS) { "invalid amount" }
}

internal fun ByteArray.hex() = "0x" + toHex()
