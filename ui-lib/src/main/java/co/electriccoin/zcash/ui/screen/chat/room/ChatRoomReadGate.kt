// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.room

import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage

// Marking read on the tap alone clears the badge and emits a receipt for messages the user never
// saw, so a visit reads the room once and only once its history is on screen.
internal class ChatRoomReadGate {
    var isHistoryLoaded: Boolean = false
        private set

    private var isVisible = false
    private var readForCurrentVisit = false

    fun onVisible() {
        if (!isVisible) readForCurrentVisit = false
        isVisible = true
    }

    fun onHidden() {
        isVisible = false
    }

    fun onHistoryLoaded() {
        isHistoryLoaded = true
    }

    fun consumeReadPermit(): Boolean {
        if (!isVisible || !isHistoryLoaded || readForCurrentVisit) return false
        readForCurrentVisit = true
        return true
    }
}

// Resolve once per visit and hold: re-deriving it walks the divider down the thread as new
// messages arrive.
internal fun firstUnreadMessageId(
    messages: List<ChatMessage>,
    unreadCount: Int,
): String? {
    if (unreadCount <= 0) return null
    return messages
        .asReversed()
        .asSequence()
        .filterNot(ChatMessage::isFromMe)
        .take(unreadCount)
        .lastOrNull()
        ?.id
}
