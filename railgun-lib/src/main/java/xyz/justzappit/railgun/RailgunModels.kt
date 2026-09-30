// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.railgun

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.types.TxHash
import java.math.BigInteger
import kotlin.time.Duration

@Serializable
enum class RailgunNetwork(
    internal val rpcUrls: List<String>,
) {
    @SerialName("sepolia")
    SEPOLIA(listOf(RailgunEndpoints.SEPOLIA_RPC_URL)),

    // No RPC yet: the page refuses to start without one.
    @SerialName("mainnet")
    MAINNET(emptyList()),
}

/** What the page may reach besides its own assets; the CSP in `web/src/index.html` lists the same hosts. */
object RailgunEndpoints {
    private const val SEPOLIA_RPC_HOST = "ethereum-sepolia-rpc.publicnode.com"
    private const val POI_NODE_HOST = "ppoi.fdi.network"
    const val SEPOLIA_RPC_URL = "https://$SEPOLIA_RPC_HOST"
    internal const val POI_NODE_URL = "https://$POI_NODE_HOST/"

    // The SDK's own: its quick sync, and the gateway it downloads proving artifacts from.
    internal val hosts = setOf(SEPOLIA_RPC_HOST, POI_NODE_HOST, "rail-squid.squids.live", "ipfs-lb.com")
}

/** A Railgun `0zk` address, its bech32m checksum verified, so a mistyped one never gets this far. */
@JvmInline
@Serializable
value class RailgunAddress(
    val value: String
) {
    init {
        require(isValid(value)) { "not a 0zk address" }
    }

    override fun toString() = value

    companion object {
        private const val PREFIX = "0zk"

        // Railgun's own limit, which its 73 bytes of version, keys and network fill exactly.
        private const val MAX_LENGTH = 127
        private const val DATA_BYTES = 73
        private const val VERSION: Byte = 1

        /** [input] as typed or pasted; bech32m may be written all in capitals, as QR codes carry it. */
        fun parseOrNull(input: String): RailgunAddress? =
            (if (input == input.uppercase()) input.lowercase() else input).takeIf(::isValid)?.let(::RailgunAddress)

        private fun isValid(value: String): Boolean =
            value.length <= MAX_LENGTH &&
                Bech32m.decode(value)?.let {
                    it.prefix == PREFIX && it.data.size == DATA_BYTES && it.data.first() == VERSION
                } == true
    }
}

/** Where a note stands with Railgun's screening: only [SPENDABLE] can be sent or unshielded. */
enum class RailgunBalanceBucket(
    internal val wireName: String
) {
    SPENDABLE("Spendable"),
    SHIELD_PENDING("ShieldPending"),
    SHIELD_BLOCKED("ShieldBlocked"),
    PROOF_SUBMITTED("ProofSubmitted"),
    MISSING_INTERNAL_POI("MissingInternalPOI"),
    MISSING_EXTERNAL_POI("MissingExternalPOI"),
    SPENT("Spent"),
}

data class RailgunTokenAmount(
    val token: Address,
    val amount: BigInteger,
)

data class RailgunBalances(
    val byBucket: Map<RailgunBalanceBucket, List<RailgunTokenAmount>>
)

@Serializable
data class RailgunGasAccount(
    val address: Address,
    @Serializable(with = DecimalSerializer::class)
    val balance: BigInteger,
)

/** Railgun's fees, in basis points of what is shielded or unshielded. */
@Serializable
data class RailgunFees(
    @SerialName("shield")
    val shieldBasisPoints: Int,
    @SerialName("unshield")
    val unshieldBasisPoints: Int,
) {
    fun unshieldFee(amount: BigInteger): BigInteger = amount * unshieldBasisPoints.toBigInteger() / DENOMINATOR

    companion object {
        val DENOMINATOR: BigInteger = BigInteger.valueOf(10_000)
    }
}

/** Where a send goes, written as the address it goes to. */
@Serializable(with = RailgunDestination.Serializer::class)
sealed interface RailgunDestination {
    val text: String

    /** Another `0zk` address: the amount and both ends stay private. */
    data class Private(
        val address: RailgunAddress
    ) : RailgunDestination {
        override val text: String get() = address.value
    }

    /** An Ethereum address, which then publicly holds the amount. */
    data class Public(
        val address: Address
    ) : RailgunDestination {
        override val text: String get() = address.checksumHex
    }

    object Serializer : KSerializer<RailgunDestination> {
        override val descriptor =
            PrimitiveSerialDescriptor("xyz.justzappit.railgun.RailgunDestination", PrimitiveKind.STRING)

        override fun deserialize(decoder: Decoder): RailgunDestination {
            val text = decoder.decodeString()
            return RailgunAddress.parseOrNull(text)?.let(::Private) ?: Public(Address.parse(text))
        }

        override fun serialize(
            encoder: Encoder,
            value: RailgunDestination
        ) = encoder.encodeString(value.text)
    }
}

data class RailgunTransfer(
    val to: RailgunDestination,
    val token: Address,
    val amount: BigInteger,
)

/** Signed by [from], the gas account, with its [nonce], and not sent: its hash is known before anyone can see it. */
data class RailgunSignedTransaction(
    val raw: String,
    val txHash: TxHash,
    val from: Address,
    val nonce: Long,
    val proofDuration: Duration?,
) {
    override fun toString() = "RailgunSignedTransaction(txHash=$txHash)"
}

@Serializable
enum class RailgunMerkletree {
    @SerialName("utxo")
    UTXO,

    @SerialName("txid")
    TXID,
}

@Serializable
enum class RailgunScanStatus {
    @SerialName("Started")
    STARTED,

    @SerialName("Updated")
    UPDATED,

    @SerialName("Complete")
    COMPLETE,

    @SerialName("Incomplete")
    INCOMPLETE,
}

sealed interface RailgunEvent {
    /** [progress] runs from 0 to 1. */
    data class Scan(
        val tree: RailgunMerkletree,
        val status: RailgunScanStatus,
        val progress: Float,
    ) : RailgunEvent

    data class Log(
        val message: String
    ) : RailgunEvent

    /** [progress] runs from 0 to 1. */
    data class Proof(
        val progress: Float,
        val status: String,
    ) : RailgunEvent
}
