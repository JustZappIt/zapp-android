@file:OptIn(ExperimentalSerializationApi::class)

package co.electriccoin.zcash.ui.common.invest.provider

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonIgnoreUnknownKeys

/**
 * What can go wrong talking to 1Click for Invest, in the terms the UI needs. Every case keeps 1Click's
 * `correlationId` when the response carried one, because support needs it (see near/intents#358).
 */
sealed class InvestApiException(
    message: String,
    val correlationId: String?,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** "No liquidity available": nobody is quoting this pair right now. Normal outside US market hours. */
    class NoPrice(
        correlationId: String?,
    ) : InvestApiException("No price available", correlationId)

    /** The login was refused for its timestamp: the clock used to sign it is off. */
    class ClockSkew(
        correlationId: String?,
    ) : InvestApiException("Login refused for its timestamp", correlationId)

    /** The session token was missing, expired or rejected; signing in again fixes it. */
    class Unauthorized(
        correlationId: String?,
    ) : InvestApiException("Not signed in", correlationId)

    /** Any other error status 1Click answered with. */
    class Api(
        val status: Int,
        val apiMessage: String?,
        correlationId: String?,
    ) : InvestApiException("1Click answered $status: ${apiMessage.orEmpty()}", correlationId)

    /**
     * Tor is on in Settings but could not start, so nothing was sent. Retrying later, or turning Tor off
     * and back on, is the way out; Invest never falls back to the direct connection on its own.
     */
    class TorUnavailable(
        cause: Throwable,
    ) : InvestApiException("Tor could not start", correlationId = null, cause = cause)

    /** The signed login was refused (bad signature or stale salt), as opposed to an expired session. */
    class LoginRefused(
        val apiMessage: String?,
        correlationId: String?,
    ) : InvestApiException("Login refused: ${apiMessage.orEmpty()}", correlationId)

    /** No usable answer at all: network or an unreadable body. */
    class Unreachable(
        cause: Throwable,
    ) : InvestApiException("1Click could not be reached", correlationId = null, cause = cause)
}

/** 1Click's error body. Every field is optional: auth errors and quote errors don't carry the same ones. */
@JsonIgnoreUnknownKeys
@Serializable
internal data class InvestErrorBody(
    @SerialName("message")
    val message: String? = null,
    @SerialName("correlationId")
    val correlationId: String? = null,
)
