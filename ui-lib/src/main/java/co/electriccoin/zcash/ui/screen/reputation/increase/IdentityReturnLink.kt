// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.reputation.increase

import xyz.justzappit.offramp.identity.IdentityReturn
import xyz.justzappit.offramp.reputation.IdentityCheck

/**
 * Where the liveness and passport widgets send the user when they are done.
 *
 * Unlike the Reclaim link these are load-bearing: the one-time `code` on them is the only handle
 * the app ever gets on the result. p2p.me's proxies exact-match them against each tenant's
 * allowlist, so they carry nothing of ours; the corridor rides in `state`. Same host rule as
 * [ReclaimReturnLink]: a bare `zcash://` lands in the scanner.
 */
object IdentityReturnLink {
    const val SCHEME = "zcash"

    fun url(check: IdentityCheck): String = "$SCHEME://${host(check)}"

    fun checkForHost(host: String?): IdentityCheck? =
        IdentityCheck.entries.firstOrNull { host(it).equals(host, ignoreCase = true) }

    internal fun parse(
        check: IdentityCheck,
        code: String?,
        error: String?,
        state: String?,
    ): IdentityReturn? {
        val cleanCode = code?.trim()?.takeIf { it.length in 1..MAX_CODE_CHARS && it.all(::isTokenCharacter) }
        val cleanError = error?.trim()?.takeIf { it.length in 1..MAX_CODE_CHARS && it.all(::isTokenCharacter) }
        val cleanState = state?.trim()?.takeIf { it.length in 1..MAX_STATE_CHARS && it.all(::isStateCharacter) }
        if (cleanCode == null && cleanError == null) return null
        return IdentityReturn(check = check, code = cleanCode, error = cleanError, state = cleanState)
    }

    private fun host(check: IdentityCheck): String =
        when (check) {
            IdentityCheck.Liveness -> "liveness-return"
            IdentityCheck.Passport -> "kyc-return"
        }

    private fun isTokenCharacter(character: Char): Boolean =
        character.isLetterOrDigit() || character == '-' || character == '_'

    private fun isStateCharacter(character: Char): Boolean = isTokenCharacter(character) || character == '.'

    private const val MAX_CODE_CHARS = 128
    private const val MAX_STATE_CHARS = 256
}
