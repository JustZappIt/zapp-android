@file:OptIn(ExperimentalSerializationApi::class)

package co.electriccoin.zcash.ui.common.invest.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonIgnoreUnknownKeys
import java.math.BigInteger

@Serializable
data class Erc191SignedData(
    @SerialName("standard")
    val standard: String,
    @SerialName("payload")
    val payload: String,
    @SerialName("signature")
    val signature: String,
)

@Serializable
data class AuthenticateRequest(
    @SerialName("signedData")
    val signedData: Erc191SignedData,
)

@JsonIgnoreUnknownKeys
@Serializable
data class AuthenticateResponse(
    @SerialName("accessToken")
    val accessToken: String,
    @SerialName("expiresIn")
    val expiresIn: Long,
)

@JsonIgnoreUnknownKeys
@Serializable
data class BalancesResponse(
    @SerialName("balances")
    val balances: List<AccountBalance>,
) {
    /** Base units by token, from the private balance only: the same token from another source can't be sold. */
    fun privateBaseUnits(): Map<String, BigInteger> =
        balances
            .filter { it.source == null || it.source == AccountBalance.PRIVATE_SOURCE }
            .groupBy { it.tokenId }
            .mapValues { (_, entries) ->
                entries.mapNotNull { it.available.toBigIntegerOrNull() }.fold(BigInteger.ZERO, BigInteger::add)
            }
}

@JsonIgnoreUnknownKeys
@Serializable
data class AccountBalance(
    @SerialName("tokenId")
    val tokenId: String,
    /** Base units, as a decimal string. */
    @SerialName("available")
    val available: String,
    @SerialName("source")
    val source: String? = null,
) {
    companion object {
        const val PRIVATE_SOURCE = "private"
    }
}

@Serializable
data class GenerateIntentRequest(
    @SerialName("type")
    val type: String = SWAP_TRANSFER,
    @SerialName("standard")
    val standard: String,
    @SerialName("signerId")
    val signerId: String,
    @SerialName("depositAddress")
    val depositAddress: String,
)

@JsonIgnoreUnknownKeys
@Serializable
data class GenerateIntentResponse(
    @SerialName("intent")
    val intent: GeneratedIntent,
    @SerialName("correlationId")
    val correlationId: String,
)

@JsonIgnoreUnknownKeys
@Serializable
data class GeneratedIntent(
    @SerialName("standard")
    val standard: String,
    /** The JSON string to sign. */
    @SerialName("payload")
    val payload: String,
)

@Serializable
data class SubmitIntentRequest(
    @SerialName("type")
    val type: String = SWAP_TRANSFER,
    @SerialName("signedData")
    val signedData: Erc191SignedData,
)

@JsonIgnoreUnknownKeys
@Serializable
data class SubmitIntentResponse(
    @SerialName("intentHash")
    val intentHash: String,
    @SerialName("correlationId")
    val correlationId: String,
)

const val SWAP_TRANSFER = "swap_transfer"
