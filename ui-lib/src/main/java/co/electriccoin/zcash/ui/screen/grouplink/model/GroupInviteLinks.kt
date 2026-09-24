// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.grouplink.model

// Routing only; the SDK does the real parsing. Both forms carry a bearer secret, so nothing here logs.
object GroupInviteLinks {
    const val HOST = "join.justzappit.xyz"
    const val HANDOFF_SCHEME = "xyz.justzappit.zapp"
    const val HANDOFF_HOST = "g"

    /** An honest link is under 200 characters. The bound keeps a hostile one out of storage. */
    const val MAX_LENGTH = 1024

    private val HTTPS =
        Regex("""^https://(?<host>[^/?#]+)(?<path>/[^?#]*)?(?:\?[^#]*)?(?<fragment>#.*)?$""", RegexOption.IGNORE_CASE)
    private val HANDOFF = Regex("""^xyz\.justzappit\.zapp://g/""", RegexOption.IGNORE_CASE)

    fun isGroupLink(raw: String): Boolean {
        val https = HTTPS.find(raw)
        return if (https != null) {
            https.part("host").equals(HOST, ignoreCase = true) && https.part("path").startsWith("/g/")
        } else {
            HANDOFF.containsMatchIn(raw)
        }
    }

    /** Drops the query, where messaging apps append tracking parameters and the secret never is. */
    fun canonical(raw: String): String? {
        if (raw.length > MAX_LENGTH || !isGroupLink(raw)) return null
        val https = HTTPS.find(raw)
        val link =
            if (https != null) {
                "https://$HOST" + https.part("path") + https.part("fragment")
            } else {
                raw.substringBefore('?')
            }
        return link.takeIf { it.toByteArray().size <= MAX_LENGTH }
    }

    private fun MatchResult.part(name: String): String = groups[name]?.value.orEmpty()
}
