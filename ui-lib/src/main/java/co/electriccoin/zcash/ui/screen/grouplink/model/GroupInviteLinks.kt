// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.grouplink.model

/**
 * Recognises group invite links before anything reads them.
 *
 * Two forms reach the app. The shared link, `https://join.justzappit.xyz/g/v1#<payload>`, and the
 * handoff the landing page opens, `xyz.justzappit.zapp://g/v1/<payload>`. Both carry a bearer
 * secret, so nothing here logs. The SDK does the real parsing; this only decides routing and trims
 * what another app may have appended.
 */
object GroupInviteLinks {
    const val HOST = "join.justzappit.xyz"
    const val HANDOFF_SCHEME = "xyz.justzappit.zapp"
    const val HANDOFF_HOST = "g"

    /** An honest link is under 200 characters. The bound keeps a hostile one out of storage. */
    const val MAX_LENGTH = 1024

    private val HTTPS =
        Regex("""^https://(?<host>[^/?#]+)(?<path>/[^?#]*)?(?:\?[^#]*)?(?<fragment>#.*)?$""", RegexOption.IGNORE_CASE)
    private val HANDOFF = Regex("""^xyz\.justzappit\.zapp://g/""", RegexOption.IGNORE_CASE)

    /** Routing only: does [raw] claim to be a group link? Says nothing about whether it reads. */
    fun isGroupLink(raw: String): Boolean {
        val https = HTTPS.find(raw)
        return if (https != null) {
            https.part("host").equals(HOST, ignoreCase = true) && https.part("path").startsWith("/g/")
        } else {
            HANDOFF.containsMatchIn(raw)
        }
    }

    /**
     * The form worth keeping: the query dropped, since the secret is never in it and messaging apps
     * append tracking parameters there. Null for anything that is not a group link or is too long
     * to be an honest one.
     */
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

    /**
     * A link found in pasted text. Tolerates the whitespace, surrounding words and sentence
     * punctuation a paste brings; a payload never starts or ends with any of those characters.
     */
    fun fromPastedText(text: String): String? =
        text
            .split(Regex("""\s+"""))
            .firstNotNullOfOrNull { word -> canonical(word.trim('<', '>', '(', ')', '"', '\'', '.', ',')) }

    private fun MatchResult.part(name: String): String = groups[name]?.value.orEmpty()
}
