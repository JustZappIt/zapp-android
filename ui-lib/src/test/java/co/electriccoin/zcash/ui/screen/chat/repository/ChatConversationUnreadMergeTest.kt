// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.repository

import co.electriccoin.zcash.ui.screen.chat.model.ChatConversation
import co.electriccoin.zcash.ui.screen.chat.model.ConversationType
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatConversationUnreadMergeTest {
    @Test
    fun `lagging refresh keeps a badge raised by the live event stream`() {
        val cached = listOf(conversation(unreadCount = 1, lastMessageTimestamp = 250L))
        val refreshed = listOf(conversation(unreadCount = 0, lastMessageTimestamp = 100L))

        val merged = refreshed.mergedWithCached(cached)

        assertEquals(1, merged.single().unreadCount)
        assertEquals(250L, merged.single().lastMessageTimestamp)
    }

    @Test
    fun `lagging refresh does not resurrect a badge the user already cleared`() {
        val cached = listOf(conversation(unreadCount = 0, lastMessageTimestamp = 100L))
        val refreshed = listOf(conversation(unreadCount = 2, lastMessageTimestamp = 100L))

        val merged = refreshed.mergedWithCached(cached)

        assertEquals(0, merged.single().unreadCount)
    }

    @Test
    fun `refresh badges a message it is the first to see`() {
        val cached = listOf(conversation(unreadCount = 0, lastMessageTimestamp = 100L))
        val refreshed = listOf(conversation(unreadCount = 1, lastMessageTimestamp = 250L))

        val merged = refreshed.mergedWithCached(cached)

        assertEquals(1, merged.single().unreadCount)
        assertEquals(250L, merged.single().lastMessageTimestamp)
    }

    @Test
    fun `lagging refresh keeps the locally queued preview and ordering`() {
        val cached = listOf(conversation(unreadCount = 0, lastMessageTimestamp = 250L, lastMessage = "sending now"))
        val refreshed = listOf(conversation(unreadCount = 0, lastMessageTimestamp = 100L, lastMessage = "older"))

        val merged = refreshed.mergedWithCached(cached)

        assertEquals("sending now", merged.single().lastMessage)
        assertEquals(250L, merged.single().lastMessageTimestamp)
    }

    @Test
    fun `conversation the cache has never seen keeps the refreshed unread count`() {
        val refreshed = listOf(conversation(unreadCount = 3, lastMessageTimestamp = 250L))

        assertEquals(3, refreshed.mergedWithCached(cached = emptyList()).single().unreadCount)
        assertEquals(3, refreshed.mergedWithCached(cached = null).single().unreadCount)
    }

    private fun conversation(
        unreadCount: Int,
        lastMessageTimestamp: Long?,
        lastMessage: String? = null,
    ) = ChatConversation(
        id = "chat",
        type = ConversationType.DIRECT,
        displayName = "chat",
        lastMessage = lastMessage,
        lastMessageTimestamp = lastMessageTimestamp,
        unreadCount = unreadCount,
    )
}
