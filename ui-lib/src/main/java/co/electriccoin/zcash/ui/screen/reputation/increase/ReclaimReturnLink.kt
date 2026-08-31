// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.reputation.increase

import xyz.justzappit.offramp.p2p.CurrencyCode
import xyz.justzappit.offramp.reclaim.ReclaimReturn
import xyz.justzappit.offramp.reputation.SocialPlatform

/**
 * Where the Reclaim Verifier sends the user when a verification finishes.
 *
 * The Verifier hands off to whatever `redirectUrl` the session template carried; sending an empty
 * one leaves the user staring at "you can now return to Zapp" in a browser, with no way back but
 * the launcher. Reclaim's own SDK validates this field with nothing more than `new URL(...)`, so a
 * private scheme is as acceptable to it as an https link — and a private scheme is the only one
 * that reaches an app rather than a web page.
 *
 * ☠ The host has to be its own thing, not a bare `zcash://`. [co.electriccoin.zcash.ui.MainActivity]
 * forwards every unrecognised `zcash://` URI to the QR scanner, so a redirect without a host of its
 * own would land returning users in the camera — worse than never coming back at all.
 *
 * The URL carries only the Reclaim session id, platform and corridor. They are untrusted routing
 * hints, never proof: the driver fetches the signed proof from Reclaim and the contract verifies
 * it. Carrying them is what lets a cold-start callback reconstruct the polling session.
 */
object ReclaimReturnLink {
    const val SCHEME = "zcash"

    const val HOST = "reclaim-return"

    const val URL = "$SCHEME://$HOST"

    const val SESSION_ID_QUERY = ReclaimReturn.SESSION_ID_QUERY
    const val PLATFORM_QUERY = ReclaimReturn.PLATFORM_QUERY
    const val CURRENCY_QUERY = ReclaimReturn.CURRENCY_QUERY

    internal fun resumeArgs(
        sessionId: String?,
        platformName: String?,
        currencyCode: String?,
    ): IncreaseReputationArgs? {
        val cleanSession =
            sessionId
                ?.trim()
                ?.takeIf { it.length in 1..MAX_SESSION_ID_CHARS && it.all(::isSessionIdCharacter) }
        val platform =
            SocialPlatform.entries.firstOrNull { it.name.equals(platformName, ignoreCase = true) }
        val currency = CurrencyCode.fromCodeOrNull(currencyCode.orEmpty())
        return if (cleanSession != null && platform != null && currency != null) {
            IncreaseReputationArgs(
                currency = currency,
                reclaimSessionId = cleanSession,
                reclaimPlatform = platform.name,
            )
        } else {
            null
        }
    }

    private fun isSessionIdCharacter(character: Char): Boolean =
        character.isLetterOrDigit() || character == '-' || character == '_'

    private const val MAX_SESSION_ID_CHARS = 200
}
