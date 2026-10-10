@file:OptIn(ExperimentalSerializationApi::class)

package co.electriccoin.zcash.ui.common.model.near

import co.electriccoin.zcash.ui.common.serialization.BigDecimalSerializer
import co.electriccoin.zcash.ui.common.serialization.NearConfidentialitySerializer
import co.electriccoin.zcash.ui.common.serialization.NearRecipientTypeSerializer
import co.electriccoin.zcash.ui.common.serialization.NearRefundTypeSerializer
import co.electriccoin.zcash.ui.common.serialization.NearSwapTypeSerializer
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonIgnoreUnknownKeys
import java.math.BigDecimal
import kotlin.time.Instant

@JsonIgnoreUnknownKeys
@Serializable
data class QuoteRequest(
    @SerialName("dry")
    val dry: Boolean,
    @SerialName("swapType")
    @Serializable(NearSwapTypeSerializer::class)
    val swapType: SwapType? = null,
    @SerialName("slippageTolerance")
    val slippageTolerance: Int,
    @SerialName("originAsset")
    val originAsset: String,
    @SerialName("depositType")
    @Serializable(NearRefundTypeSerializer::class)
    val depositType: RefundType? = null,
    @SerialName("destinationAsset")
    val destinationAsset: String,
    @SerialName("amount")
    @Serializable(BigDecimalSerializer::class)
    val amount: BigDecimal,
    @SerialName("refundTo")
    val refundTo: String,
    @SerialName("refundType")
    @Serializable(NearRefundTypeSerializer::class)
    val refundType: RefundType? = null,
    @SerialName("recipient")
    val recipient: String,
    @SerialName("recipientType")
    @Serializable(NearRecipientTypeSerializer::class)
    val recipientType: RecipientType? = null,
    @SerialName("deadline")
    val deadline: Instant,
    @SerialName("quoteWaitingTimeMs")
    val quoteWaitingTimeMs: Int? = null,
    @SerialName("appFees")
    val appFees: List<AppFee>,
    @SerialName("referral")
    val referral: String? = null,
    // Only Invest sets this. Never encoded when null, so a swap or bridge request stays byte-for-byte what it
    // was before the field existed.
    @SerialName("confidentiality")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @Serializable(NearConfidentialitySerializer::class)
    val confidentiality: Confidentiality? = null,
)

@JsonIgnoreUnknownKeys
@Serializable
data class AppFee(
    @SerialName("recipient")
    val recipient: String,
    @SerialName("fee")
    val fee: Int
)

enum class RefundType(
    val apiValue: String
) {
    ORIGIN_CHAIN("ORIGIN_CHAIN"),
    INTENTS("INTENTS"),
    CONFIDENTIAL_INTENTS("CONFIDENTIAL_INTENTS"),
}

enum class SwapType(
    val apiValue: String
) {
    EXACT_INPUT("EXACT_INPUT"),
    EXACT_OUTPUT("EXACT_OUTPUT"),
    FLEX_INPUT("FLEX_INPUT"),
}

enum class RecipientType(
    val apiValue: String
) {
    DESTINATION_CHAIN("DESTINATION_CHAIN"),
    INTENTS("INTENTS"),
    CONFIDENTIAL_INTENTS("CONFIDENTIAL_INTENTS"),
}

/**
 * How much of a swap into or out of NEAR Confidential Intents stays private. 1Click rewrites
 * [PUBLIC] to [BASIC] whenever a confidential side is involved (observed 2026-09-25). Every swap echoes
 * one of these, so an unknown value decodes to null rather than failing the quote.
 */
enum class Confidentiality(
    val apiValue: String
) {
    PUBLIC("public"),
    BASIC("basic"),
    ADVANCED("advanced"),
}
