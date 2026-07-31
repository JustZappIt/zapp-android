package co.electriccoin.zcash.ui.screen.chat.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class ChatMessageListTest {
    private fun message(id: String, timestamp: Long) =
        ChatMessage(id = id, conversationId = "conv", content = "m-$id", timestamp = timestamp)

    @Test
    fun `plusMessage inserts a late older message chronologically`() {
        val list = listOf(message("old", 1_000), message("new", 3_000))

        val result = list.plusMessage(message("mid", 2_000))

        assertEquals(listOf("old", "mid", "new"), result.map { it.id })
    }

    @Test
    fun `plusMessage drops a duplicate id`() {
        val list = listOf(message("a", 1_000))

        val result = list.plusMessage(message("a", 2_000))

        assertSame(list, result)
    }

    @Test
    fun `plusMessage keeps arrival order for equal timestamps`() {
        val list = listOf(message("z-first", 1_000))

        val result = list.plusMessage(message("a-second", 1_000))

        assertEquals(listOf("z-first", "a-second"), result.map { it.id })
    }

    @Test
    fun `mergedWithHistory keeps live rows over their persisted twins`() {
        val live = listOf(message("a", 1_000).copy(status = MessageStatus.READ))
        val history = listOf(message("a", 1_000), message("b", 2_000))

        val result = live.mergedWithHistory(history)

        assertEquals(listOf("a", "b"), result.map { it.id })
        assertEquals(MessageStatus.READ, result.first().status)
    }

    @Test
    fun `mergedWithHistory sorts the union chronologically`() {
        val live = listOf(message("live", 5_000))
        val history = listOf(message("h2", 2_000), message("h1", 1_000))

        val result = live.mergedWithHistory(history)

        assertEquals(listOf("h1", "h2", "live"), result.map { it.id })
    }

    @Test
    fun `mergedWithHistory into an empty list returns sorted history`() {
        val history = listOf(message("h2", 2_000), message("h1", 1_000))

        val result = emptyList<ChatMessage>().mergedWithHistory(history)

        assertEquals(listOf("h1", "h2"), result.map { it.id })
    }

    @Test
    fun `mergedWithHistory removes duplicate ids within persisted history`() {
        val history =
            listOf(
                message("a", 1_000),
                message("a", 2_000),
                message("b", 3_000),
            )

        val result = emptyList<ChatMessage>().mergedWithHistory(history)

        assertEquals(listOf("a", "b"), result.map { it.id })
        assertEquals(1_000, result.first().timestamp)
    }
}
