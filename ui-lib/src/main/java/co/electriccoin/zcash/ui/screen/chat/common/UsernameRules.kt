// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.common

/**
 * Chat display-name rules. The create path (onboarding's `UsernameEntryScreen`) and the
 * restore path (`ZappRestoreFlow`) MUST agree — a restored identity that round-trips
 * across devices needs to land with a name the create flow would have accepted, otherwise the
 * two paths produce different on-disk shapes.
 */
object UsernameRules {
    const val MIN_LENGTH = 3
    const val MAX_LENGTH = 20

    private val ALLOWED = Regex("[a-z0-9_]*")

    /** Strip / lowercase keystrokes as the user types so the field only ever holds valid chars. */
    fun sanitize(raw: String): String =
        raw.lowercase().filter { it.isLetterOrDigit() || it == '_' }

    fun isValid(name: String): Boolean =
        name.length in MIN_LENGTH..MAX_LENGTH && ALLOWED.matches(name)
}
