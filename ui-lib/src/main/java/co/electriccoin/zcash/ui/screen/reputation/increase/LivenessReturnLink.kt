// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.reputation.increase

import xyz.justzappit.offramp.liveness.LivenessReturn

/**
 * Where the liveness widget sends the user when it is done.
 *
 * Unlike the Reclaim link this one is load-bearing: the one-time `code` on it is the only handle
 * the app ever gets on the result. It is exact-matched against the tenant's allowlist, so it
 * carries nothing of ours; the corridor rides in `state`. Same host rule as [ReclaimReturnLink] —
 * a bare `zcash://` lands in the scanner.
 */
object LivenessReturnLink {
    const val SCHEME = "zcash"

    const val HOST = "liveness-return"

    const val URL = "$SCHEME://$HOST"

    internal fun parse(
        code: String?,
        error: String?,
        state: String?,
    ): LivenessReturn? {
        val cleanCode = code?.trim()?.takeIf { it.length in 1..MAX_CODE_CHARS && it.all(::isTokenCharacter) }
        val cleanError = error?.trim()?.takeIf { it.length in 1..MAX_CODE_CHARS && it.all(::isTokenCharacter) }
        val cleanState = state?.trim()?.takeIf { it.length in 1..MAX_STATE_CHARS && it.all(::isStateCharacter) }
        if (cleanCode == null && cleanError == null) return null
        return LivenessReturn(code = cleanCode, error = cleanError, state = cleanState)
    }

    private fun isTokenCharacter(character: Char): Boolean =
        character.isLetterOrDigit() || character == '-' || character == '_'

    private fun isStateCharacter(character: Char): Boolean = isTokenCharacter(character) || character == '.'

    private const val MAX_CODE_CHARS = 128
    private const val MAX_STATE_CHARS = 256
}
