@file:OptIn(ExperimentalSerializationApi::class)

package co.electriccoin.zcash.ui.common.invest.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonIgnoreUnknownKeys

/** The `erc191` member of 1Click's `MultiPayload`: what `authenticate` and `submit-intent` accept as signed data. */
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

/**
 * `POST /v0/auth/authenticate` → 201. Lifetimes observed 2026-09-25/26: access 900 s, refresh 604,800 s.
 * The refresh token is deliberately not modelled: Invest keeps no stored credential and signs in again.
 */
@JsonIgnoreUnknownKeys
@Serializable
data class AuthenticateResponse(
    @SerialName("accessToken")
    val accessToken: String,
    @SerialName("expiresIn")
    val expiresIn: Long,
)

/** `GET /v0/account/balances`: `{"balances": [...]}`, empty for a new account. */
@JsonIgnoreUnknownKeys
@Serializable
data class BalancesResponse(
    @SerialName("balances")
    val balances: List<AccountBalance>,
)

@JsonIgnoreUnknownKeys
@Serializable
data class AccountBalance(
    @SerialName("tokenId")
    val tokenId: String,
    /** Base units, as a decimal string. */
    @SerialName("available")
    val available: String,
    /** `"private"` for a confidential balance. */
    @SerialName("source")
    val source: String? = null,
)

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

/**
 * `POST /v0/generate-intent` → `{"intent": MultiPayload, "correlationId"}`. For `erc191`,
 * [GeneratedIntent.payload] is the JSON string to sign.
 */
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

/** The only intent type `generate-intent` and `submit-intent` accept (a 400 names it on anything else). */
const val SWAP_TRANSFER = "swap_transfer"
