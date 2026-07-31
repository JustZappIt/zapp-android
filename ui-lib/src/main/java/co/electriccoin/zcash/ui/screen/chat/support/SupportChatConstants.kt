// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.support

import androidx.annotation.StringRes
import co.electriccoin.zcash.ui.R

/**
 * User-visible categories shown on the topic-picker screen.
 *
 * [protocolKey] is the stable identifier sent over the wire (as part of the conversation
 * displayName and the `[Category: …]` marker message) — must NEVER be localized.
 * [displayNameRes] is the localized label rendered on the picker — never sent over the wire.
 * [greetingRes] is the bot greeting shown after the category is picked.
 */
enum class SupportCategory(
    val protocolKey: String,
    @param:StringRes val displayNameRes: Int,
    @param:StringRes val greetingRes: Int,
) {
    PROBLEM(
        protocolKey = "Problem",
        displayNameRes = R.string.support_chat_category_problem,
        greetingRes = R.string.support_chat_greeting_problem,
    ),
    FEEDBACK(
        protocolKey = "Feedback",
        displayNameRes = R.string.support_chat_category_feedback,
        greetingRes = R.string.support_chat_greeting_feedback,
    ),
    OTHER(
        protocolKey = "Other",
        displayNameRes = R.string.support_chat_category_other,
        greetingRes = R.string.support_chat_greeting_other,
    );

    companion object {
        fun fromProtocolKey(key: String): SupportCategory? =
            entries.firstOrNull { it.protocolKey == key }
    }
}

object SupportChatConstants {
    const val SUPPORT_PUBLIC_KEY = "20dae657c99f8504b4ce052a39b2a6bf3b54023cb56ee2245d9904e4ee0f0c48"

    /**
     * Prefix set on every support-ticket conversation's displayName. Sent over the wire as
     * part of the group invite so it lands on both peers; the support agent's device relies
     * on it because `participantIds` excludes the local user's own key.
     */
    const val DISPLAY_NAME_PREFIX = "Support: "

    /**
     * Prefix applied to automated messages before they go over the wire. The receiving side
     * uses this to render the message as if the bot/agent sent it even when the sending
     * device was the user's own.
     */
    const val BOT_PREFIX = "[Zapp]: "

    /**
     * Prefix of the category-selection marker sent when the user picks a topic.
     * Used to recover the category on reload.
     */
    const val CATEGORY_MARKER_PREFIX = "[Category: "
    const val CATEGORY_MARKER_SUFFIX = "]"

    /** Builds the `[Category: <key>]` marker for the given category. */
    fun categoryMarker(category: SupportCategory): String =
        "$CATEGORY_MARKER_PREFIX${category.protocolKey}$CATEGORY_MARKER_SUFFIX"

    /** Parses a category marker message back into the originating category, if recognisable. */
    fun parseCategoryMarker(message: String): SupportCategory? {
        if (!message.startsWith(CATEGORY_MARKER_PREFIX) || !message.endsWith(CATEGORY_MARKER_SUFFIX)) {
            return null
        }
        val key =
            message
                .removePrefix(CATEGORY_MARKER_PREFIX)
                .removeSuffix(CATEGORY_MARKER_SUFFIX)
        return SupportCategory.fromProtocolKey(key)
    }

    /**
     * Returns true when the conversation should be treated as a support ticket on this device.
     *
     * The two sides need different signals: the user's device must require the support agent's
     * key in [participantIds] (`displayName` alone is spoofable), while the support agent's
     * device falls back to the displayName prefix because its own key is excluded from the
     * SDK's participant list.
     */
    fun isSupportConversation(
        displayName: String,
        participantIds: List<String>,
        localPublicKey: String?,
    ): Boolean {
        val viewerIsSupportAgent = localPublicKey == SUPPORT_PUBLIC_KEY
        return if (viewerIsSupportAgent) {
            displayName.startsWith(DISPLAY_NAME_PREFIX)
        } else {
            participantIds.contains(SUPPORT_PUBLIC_KEY)
        }
    }
}
