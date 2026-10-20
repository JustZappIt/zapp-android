@file:OptIn(ExperimentalSerializationApi::class)

package co.electriccoin.zcash.ui.common.invest.provider

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonIgnoreUnknownKeys

/** A failed 1Click call, keeping its `correlationId` for support. */
sealed class InvestApiException(
    message: String,
    val correlationId: String?,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** "No liquidity available": nobody is quoting this pair right now. */
    class NoPrice(
        correlationId: String?,
    ) : InvestApiException("No price available", correlationId)

    /** The login was refused for its timestamp. */
    class ClockSkew(
        correlationId: String?,
    ) : InvestApiException("Login refused for its timestamp", correlationId)

    /** The session token was missing, expired or rejected. */
    class Unauthorized(
        correlationId: String?,
    ) : InvestApiException("Not signed in", correlationId)

    class Api(
        val status: Int,
        val apiMessage: String?,
        correlationId: String?,
    ) : InvestApiException("1Click answered $status: ${apiMessage.orEmpty()}", correlationId)

    /** Tor is on but could not start, so nothing was sent. */
    class TorUnavailable(
        cause: Throwable,
    ) : InvestApiException("Tor could not start", correlationId = null, cause = cause)

    /** The signed login itself was refused, e.g. for a stale salt. */
    class LoginRefused(
        val apiMessage: String?,
        correlationId: String?,
    ) : InvestApiException("Login refused: ${apiMessage.orEmpty()}", correlationId)

    /** The wallet was reset during the sign-in, so the call was abandoned. */
    class SessionReset : InvestApiException("The wallet was reset while signing in", correlationId = null)

    /** No usable answer: network failure, timeout or an unreadable body. */
    class Unreachable(
        cause: Throwable,
    ) : InvestApiException("1Click could not be reached", correlationId = null, cause = cause)
}

@JsonIgnoreUnknownKeys
@Serializable
internal data class InvestErrorBody(
    @SerialName("message")
    val message: String? = null,
    @SerialName("correlationId")
    val correlationId: String? = null,
)
