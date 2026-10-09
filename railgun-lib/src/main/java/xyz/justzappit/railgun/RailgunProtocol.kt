// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.railgun

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import xyz.justzappit.evm.types.Address
import java.math.BigInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Every call the page answers, and how long it may take; its side is `railgun-lib/web/src/index.js`. */
@Serializable
internal enum class RailgunMethod(
    val timeout: Duration,
) {
    @SerialName("start")
    START(2.minutes),

    @SerialName("openWallet")
    OPEN_WALLET(1.minutes),

    @SerialName("refresh")
    REFRESH(10.minutes),

    @SerialName("transfer")
    TRANSFER(10.minutes),

    @SerialName("unshield")
    UNSHIELD(10.minutes),

    @SerialName("broadcasterFee")
    BROADCASTER_FEE(2.minutes),

    @SerialName("reverseCost")
    REVERSE_COST(30.seconds),

    @SerialName("prepareReverse")
    PREPARE_REVERSE(10.minutes),
}

@Serializable
internal data class StartParams(
    val network: RailgunNetwork,
    val rpcUrls: List<String>,
    val poiNodeUrls: List<String>,
    val debug: Boolean,
)

@Serializable
internal data class StartResult(
    val fees: RailgunFees
)

@Serializable
internal class OpenWalletParams(
    val encryptionKey: String,
    val mnemonic: String,
)

@Serializable
internal data class OpenWalletResult(
    val address: RailgunAddress
)

@Serializable
internal data class WireTokenAmount(
    val token: Address,
    @Serializable(with = DecimalSerializer::class)
    val amount: BigInteger,
)

@Serializable
internal data class TransferParams(
    val to: RailgunAddress,
    val token: Address,
    @Serializable(with = DecimalSerializer::class)
    val amount: BigInteger,
    @Serializable(with = DecimalSerializer::class)
    val fee: BigInteger,
    val broadcaster: BroadcasterParams,
)

@Serializable
internal data class UnshieldParams(
    val to: Address,
    val token: Address,
    @Serializable(with = DecimalSerializer::class)
    val amount: BigInteger,
    @Serializable(with = DecimalSerializer::class)
    val fee: BigInteger,
    val broadcaster: BroadcasterParams,
)

@Serializable
internal data class BroadcasterFeeParams(
    val to: RailgunDestination,
    val token: Address,
    @Serializable(with = DecimalSerializer::class)
    val amount: BigInteger,
    val broadcaster: BroadcasterParams,
)

@Serializable
internal data class BroadcasterFeeResult(
    @Serializable(with = DecimalSerializer::class)
    val fee: BigInteger,
)

@Serializable
internal data class BroadcasterParams(
    val chainId: Long,
    val railgunProxy: Address,
    val railgunAddress: RailgunAddress,
    val token: Address,
    @Serializable(with = DecimalSerializer::class)
    val minFee: BigInteger,
    @Serializable(with = DecimalSerializer::class)
    val feePerUnitGas: BigInteger?,
    @Serializable(with = DecimalSerializer::class)
    val maxGasPrice: BigInteger,
) {
    constructor(broadcaster: RailgunBroadcaster) : this(
        chainId = broadcaster.chainId,
        railgunProxy = broadcaster.railgunProxy,
        railgunAddress = broadcaster.railgunAddress,
        token = broadcaster.feeToken,
        minFee = broadcaster.minFee,
        feePerUnitGas = broadcaster.feePerUnitGas,
        maxGasPrice = broadcaster.maxGasPrice,
    )
}

@Serializable
internal data class RelayedResult(
    val chainId: Long,
    val to: Address,
    val data: String,
    @Serializable(with = DecimalSerializer::class)
    val value: BigInteger,
    val spends: List<RailgunNullifiers>,
)

internal object RailgunProtocol {
    const val INIT = "zapp-railgun-init"

    val json =
        Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            explicitNulls = false
        }

    sealed interface Message {
        data object Ready : Message

        data class Event(
            val event: RailgunEvent
        ) : Message

        data class Reply(
            val id: Long,
            val result: JsonElement
        ) : Message

        data class Failure(
            val id: Long,
            val error: RailgunException
        ) : Message
    }

    fun request(
        id: Long,
        method: RailgunMethod,
        params: JsonElement
    ): String = json.encodeToString(Request.serializer(), Request(id, method, params))

    /** Null for what this build can't read, except a reply: that one still fails its call. */
    fun decode(data: String?): Message? {
        val message = data?.let(::parse) as? JsonObject ?: return null
        return try {
            json.decodeFromJsonElement(Envelope.serializer(), message).toMessage()
        } catch (e: IllegalArgumentException) {
            (message["id"] as? JsonPrimitive)?.longOrNull?.let {
                Message.Failure(it, RailgunException.Protocol("the page's reply is unreadable", e))
            }
        }
    }

    private fun parse(data: String): JsonElement? =
        try {
            json.parseToJsonElement(data)
        } catch (ignored: SerializationException) {
            null
        }

    private fun Envelope.toMessage(): Message? =
        when {
            event != null -> decodeEvent(event, data ?: JsonNull)
            id == null -> null
            error != null -> Message.Failure(id, error.toException())
            else -> Message.Reply(id, result ?: JsonNull)
        }

    private fun decodeEvent(
        event: String,
        data: JsonElement
    ): Message? =
        when (event) {
            "ready" -> Message.Ready
            "log" -> Message.Event(RailgunEvent.Log(json.decodeFromJsonElement(String.serializer(), data)))
            "scan" -> Message.Event(json.decodeFromJsonElement(ScanData.serializer(), data).toEvent())
            "proof" -> Message.Event(json.decodeFromJsonElement(ProofData.serializer(), data).toEvent())
            else -> null
        }

    @Serializable
    private data class Request(
        val id: Long,
        val method: RailgunMethod,
        val params: JsonElement,
    )

    @Serializable
    private data class Envelope(
        val id: Long? = null,
        val result: JsonElement? = null,
        val error: WireError? = null,
        val event: String? = null,
        val data: JsonElement? = null,
    )

    @Serializable
    private data class WireError(
        val code: Code = Code.FAILED,
        val message: String = "the page failed",
    ) {
        fun toException(): RailgunException =
            when (code) {
                Code.BAD_REQUEST -> RailgunException.Protocol(message)
                Code.FAILED -> RailgunException.Failed(message)
            }

        @Serializable
        enum class Code { BAD_REQUEST, FAILED }
    }

    @Serializable
    private data class ScanData(
        val tree: RailgunMerkletree,
        val status: RailgunScanStatus,
        val progress: Float = 0f,
    ) {
        fun toEvent() = RailgunEvent.Scan(tree, status, progress)
    }

    @Serializable
    private data class ProofData(
        val progress: Float = 0f,
        val status: String = "",
    ) {
        fun toEvent() = RailgunEvent.Proof(progress, status)
    }
}

/** Token amounts travel as decimal strings, since they outgrow a JSON number. */
object DecimalSerializer : KSerializer<BigInteger> {
    override val descriptor = PrimitiveSerialDescriptor("xyz.justzappit.railgun.Decimal", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: BigInteger
    ) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): BigInteger {
        val value = decoder.decodeString()
        if (value.isEmpty() || !value.all { it in '0'..'9' }) throw SerializationException("not a decimal amount")
        return BigInteger(value)
    }
}
