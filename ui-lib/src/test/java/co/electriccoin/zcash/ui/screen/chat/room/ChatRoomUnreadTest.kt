// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.room

import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatRoomUnreadTest {
    @Test
    fun `unread boundary starts at earliest unread incoming message`() {
        val messages =
            listOf(
                message("read-incoming"),
                message("unread-one"),
                message("outgoing", isFromMe = true),
                message("unread-two"),
            )

        assertEquals("unread-one", firstUnreadMessageId(messages, unreadCount = 2))
    }

    @Test
    fun `unread boundary ignores outgoing messages`() {
        val messages =
            listOf(
                message("read-incoming"),
                message("outgoing", isFromMe = true),
                message("unread-incoming"),
            )

        assertEquals("unread-incoming", firstUnreadMessageId(messages, unreadCount = 1))
    }

    @Test
    fun `unread boundary falls back to the oldest incoming message it has`() {
        val messages = listOf(message("only-incoming"))

        assertEquals("only-incoming", firstUnreadMessageId(messages, unreadCount = 5))
    }

    @Test
    fun `no unread count produces no boundary`() {
        assertNull(firstUnreadMessageId(listOf(message("incoming")), unreadCount = 0))
    }

    @Test
    fun `loading history while the room is hidden does not permit a read`() {
        val gate = ChatRoomReadGate()

        gate.onHistoryLoaded()

        assertFalse(gate.consumeReadPermit())
    }

    @Test
    fun `a visit reads the room exactly once`() {
        val gate = ChatRoomReadGate()
        gate.onHistoryLoaded()
        gate.onVisible()

        assertTrue(gate.consumeReadPermit())
        assertFalse(gate.consumeReadPermit())
    }

    @Test
    fun `returning to a loaded room reads it again`() {
        val gate = ChatRoomReadGate()
        gate.onHistoryLoaded()
        gate.onVisible()
        gate.consumeReadPermit()

        gate.onHidden()
        gate.onVisible()

        assertTrue(gate.consumeReadPermit())
    }

    @Test
    fun `leaving before history loads prevents a late read`() {
        val gate = ChatRoomReadGate()
        gate.onVisible()
        gate.onHidden()
        gate.onHistoryLoaded()

        assertFalse(gate.consumeReadPermit())
    }

    @Test
    fun `a failed history load leaves the room loadable so a later visit retries`() {
        val gate = ChatRoomReadGate()
        gate.onVisible()

        assertFalse(gate.isHistoryLoaded)
        assertFalse(gate.consumeReadPermit())
    }

    private fun message(
        id: String,
        isFromMe: Boolean = false,
    ) = ChatMessage(
        id = id,
        conversationId = "conversation",
        content = id,
        isFromMe = isFromMe,
    )
}
