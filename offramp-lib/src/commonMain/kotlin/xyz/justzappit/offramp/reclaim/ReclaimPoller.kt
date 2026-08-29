// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.reclaim

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json

/** Why polling stopped. Only [Proofs] is a success; the rest each need their own sentence to the user. */
sealed interface ReclaimPollResult {
    data class Proofs(
        val proofs: List<ReclaimSessionProof>
    ) : ReclaimPollResult

    /** The provider ran and refused: the account is too new. A *successful* response, not an error. */
    data object CriteriaNotMet : ReclaimPollResult

    data object GenerationFailed : ReclaimPollResult

    /** Expired, or never existed. Sessions live about ten minutes and cannot be extended. */
    data object SessionGone : ReclaimPollResult

    data object TimedOut : ReclaimPollResult
}

/**
 * Watches one Reclaim session. The endpoint is unauthenticated and needs no SDK — resuming a
 * session by id never touches the app secret, it only reads what the attestors published.
 *
 * Bounded on purpose: past roughly ten minutes the session is dead whatever the app is showing, so
 * this returns [ReclaimPollResult.TimedOut] rather than spinning against a session that can no
 * longer produce anything.
 */
class ReclaimPoller(
    private val httpClient: HttpClient,
    private val baseUrl: String = ReclaimSessionMinter.RECLAIM_API_BASE,
    private val intervalMillis: Long = DEFAULT_INTERVAL_MS,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MS,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun await(sessionId: String): ReclaimPollResult {
        var waited = 0L
        while (waited < timeoutMillis) {
            when (val outcome = pollOnce(sessionId)) {
                null -> Unit
                else -> return outcome
            }
            delay(intervalMillis)
            waited += intervalMillis
        }
        return ReclaimPollResult.TimedOut
    }

    /** One read. Null means "nothing yet" — the only case that keeps the loop going. */
    @Suppress("ReturnCount")
    internal suspend fun pollOnce(sessionId: String): ReclaimPollResult? {
        val body =
            try {
                httpClient.get("$baseUrl$PATH_SESSION$sessionId").bodyAsText()
            } catch (ignored: kotlinx.io.IOException) {
                // A dropped request mid-verification is not a verdict; the next tick asks again.
                return null
            }
        // Checked before parsing: an expired session answers with a message and no session object,
        // and the shapes differ enough that decoding it as a status would only obscure the reason.
        if (body.contains(SESSION_NOT_FOUND, ignoreCase = true)) return ReclaimPollResult.SessionGone
        val status =
            try {
                json.decodeFromString(ReclaimSessionStatus.serializer(), body)
            } catch (ignored: kotlinx.serialization.SerializationException) {
                return null
            }
        val session = status.session ?: return null
        if (session.proofs.isNotEmpty()) {
            return if (ReclaimProofTransform.isCriteriaNotMet(session.proofs)) {
                ReclaimPollResult.CriteriaNotMet
            } else {
                ReclaimPollResult.Proofs(session.proofs)
            }
        }
        if (session.statusV2 == PROOF_GENERATION_FAILED) return ReclaimPollResult.GenerationFailed
        return null
    }

    private companion object {
        const val PATH_SESSION = "/api/sdk/session/"
        const val SESSION_NOT_FOUND = "Session not found"
        const val PROOF_GENERATION_FAILED = "PROOF_GENERATION_FAILED"
        const val DEFAULT_INTERVAL_MS = 5_000L
        const val DEFAULT_TIMEOUT_MS = 10 * 60 * 1_000L
    }
}
