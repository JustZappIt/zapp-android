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

    // A send inserts an optimistic row and the worklet answers with its own id. The row must not
    // re-mount over that swap: the list keys rows on rowId, so it has to survive the reconcile.

    @Test
    fun `reconciled swaps the id in place and keeps the row`() {
        val list = listOf(message("a", 1_000), message("local:1", 3_000))

        val result = list.reconciled("local:1", message("srv-1", 2_500).copy(status = MessageStatus.SENT))

        assertEquals(listOf("a", "srv-1"), result.map { it.id })
        assertEquals(listOf("a", "local:1"), result.map { it.rowId })
        assertEquals(2_500, result.last().timestamp)
        assertEquals(MessageStatus.SENT, result.last().status)
    }

    @Test
    fun `reconciled re-sorts by the worklet timestamp`() {
        val list = listOf(message("local:1", 3_000), message("b", 4_000))

        val result = list.reconciled("local:1", message("srv-1", 5_000))

        assertEquals(listOf("b", "srv-1"), result.map { it.id })
    }

    @Test
    fun `reconciled folds an already surfaced persisted row into the optimistic one`() {
        val list =
            listOf(
                message("local:1", 3_000).copy(status = MessageStatus.SENDING),
                message("srv-1", 2_500).copy(status = MessageStatus.DELIVERED),
            )

        val result = list.reconciled("local:1", message("srv-1", 2_500).copy(status = MessageStatus.SENT))

        assertEquals(listOf("srv-1"), result.map { it.id })
        assertEquals("local:1", result.single().rowId)
        assertEquals(MessageStatus.DELIVERED, result.single().status)
    }

    @Test
    fun `reconciled adds the persisted row when the optimistic one is gone`() {
        val list = listOf(message("a", 1_000))

        val result = list.reconciled("local:1", message("srv-1", 2_000))

        assertEquals(listOf("a", "srv-1"), result.map { it.id })
    }
}
