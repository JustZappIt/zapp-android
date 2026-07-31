package co.electriccoin.zcash.ui.screen.chat.model

import xyz.justzappit.zappmessaging.models.ZMMessage
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatModelsTest {
    @Test
    fun `capDisplayName truncates strings longer than the limit`() {
        val long = "a".repeat(MAX_DISPLAY_NAME_LENGTH + 50)

        val capped = long.capDisplayName()

        assertEquals(MAX_DISPLAY_NAME_LENGTH, capped.length)
    }

    @Test
    fun `capDisplayName preserves strings at exactly the limit`() {
        val boundary = "a".repeat(MAX_DISPLAY_NAME_LENGTH)

        val capped = boundary.capDisplayName()

        assertEquals(boundary, capped)
    }

    @Test
    fun `capDisplayName preserves short strings unchanged`() {
        val short = "alice"

        val capped = short.capDisplayName()

        assertEquals("alice", capped)
    }

    @Test
    fun `capDisplayName preserves empty string`() {
        val capped = "".capDisplayName()

        assertEquals("", capped)
    }

    @Test
    fun `capDisplayName truncates from the start preserving the prefix`() {
        val long = "alice_" + "x".repeat(MAX_DISPLAY_NAME_LENGTH)

        val capped = long.capDisplayName()

        assertEquals("alice_", capped.substring(0, "alice_".length))
        assertEquals(MAX_DISPLAY_NAME_LENGTH, capped.length)
    }

    @Test
    fun `saved contact name wins over direct conversation fallback`() {
        val conversation = directConversation(displayName = "remote_name", publicKey = "ABCDEF")
        val contacts = listOf(ChatContact(publicKey = "0xabcdef", name = "Local name")).byPublicKey()

        assertEquals("Local name", conversation.resolveDisplayName(contacts))
    }

    @Test
    fun `direct conversation uses remote fallback without saved contact`() {
        val conversation = directConversation(displayName = "remote_name", publicKey = "abcdef")

        assertEquals("remote_name", conversation.resolveDisplayName(emptyMap()))
    }

    @Test
    fun `direct conversation resolves the peer alias when our own key precedes the peer`() {
        val conversation =
            ChatConversation(
                id = "direct",
                type = ConversationType.DIRECT,
                displayName = "remote_name",
                participantIds = listOf("123456", "abcdef"),
            )
        val contacts = listOf(ChatContact(publicKey = "abcdef", name = "Local name")).byPublicKey()

        assertEquals("Local name", conversation.resolveDisplayName(contacts))
    }

    @Test
    fun `group conversation keeps its conversation name`() {
        val conversation =
            ChatConversation(
                id = "group",
                type = ConversationType.GROUP,
                displayName = "Shared group",
                participantIds = listOf("abcdef"),
            )
        val contacts = listOf(ChatContact(publicKey = "abcdef", name = "Local name")).byPublicKey()

        assertEquals("Shared group", conversation.resolveDisplayName(contacts))
    }

    @Test
    fun `incoming message sender uses saved contact name`() {
        val message =
            ChatMessage(
                id = "message",
                conversationId = "conversation",
                content = "hello",
                senderName = "remote_name",
                senderId = "ABCDEF",
            )
        val contacts = listOf(ChatContact(publicKey = "0xabcdef", name = "Local name")).byPublicKey()

        assertEquals("Local name", message.resolveSenderName(contacts).senderName)
    }

    @Test
    fun `outgoing message keeps its sender name`() {
        val message =
            ChatMessage(
                id = "message",
                conversationId = "conversation",
                content = "hello",
                senderName = "Me",
                senderId = "abcdef",
                isFromMe = true,
            )
        val contacts = listOf(ChatContact(publicKey = "abcdef", name = "Local name")).byPublicKey()

        assertEquals("Me", message.resolveSenderName(contacts).senderName)
    }

    @Test
    fun `quoted reply uses saved name for the original sender`() {
        val original =
            ChatMessage(
                id = "original",
                conversationId = "conversation",
                content = "hello",
                senderName = "remote_name",
                senderId = "abcdef",
            )
        val reply =
            ChatMessage(
                id = "reply",
                conversationId = "conversation",
                content = "reply",
                replyToId = original.id,
                replyToSenderName = "remote_name",
            )
        val contacts = listOf(ChatContact(publicKey = "abcdef", name = "Local name")).byPublicKey()

        val resolved = listOf(original, reply).resolveSenderNames(contacts)

        assertEquals("Local name", resolved.last().replyToSenderName)
    }

    @Test
    fun `delivery status advances without late event downgrades`() {
        assertEquals(MessageStatus.QUEUED, MessageStatus.SENDING.advanceTo(MessageStatus.QUEUED))
        assertEquals(MessageStatus.SENT, MessageStatus.QUEUED.advanceTo(MessageStatus.SENT))
        assertEquals(MessageStatus.DELIVERED, MessageStatus.SENT.advanceTo(MessageStatus.DELIVERED))
        assertEquals(MessageStatus.SENT, MessageStatus.SENT.advanceTo(MessageStatus.QUEUED))
        assertEquals(MessageStatus.DELIVERED, MessageStatus.DELIVERED.advanceTo(MessageStatus.SENT))
        assertEquals(MessageStatus.READ, MessageStatus.READ.advanceTo(MessageStatus.SENT))
    }

    @Test
    fun `delivery failure is surfaced from every nonterminal state`() {
        assertEquals(MessageStatus.FAILED, MessageStatus.SENDING.advanceTo(MessageStatus.FAILED))
        assertEquals(MessageStatus.FAILED, MessageStatus.QUEUED.advanceTo(MessageStatus.FAILED))
        assertEquals(MessageStatus.FAILED, MessageStatus.SENT.advanceTo(MessageStatus.FAILED))
        assertEquals(MessageStatus.FAILED, MessageStatus.DELIVERED.advanceTo(MessageStatus.FAILED))
    }

    @Test
    fun `disabled receipts hide read state without mutating original message`() {
        val message =
            ChatMessage(
                id = "message",
                conversationId = "conversation",
                content = "hello",
                isFromMe = true,
                status = MessageStatus.READ,
            )

        val hidden = message.withReadReceiptVisibility(isVisible = false)

        assertEquals(MessageStatus.DELIVERED, hidden.status)
        assertEquals(MessageStatus.READ, message.status)
    }

    @Test
    fun `persisted outgoing status survives reload`() {
        val queued =
            ZMMessage(
                id = "message",
                conversationId = "conversation",
                senderId = "me",
                content = "hello",
                isFromMe = true,
                status = "queued",
            )

        assertEquals(MessageStatus.QUEUED, ChatMessage.from(queued).status)
        assertEquals(MessageStatus.SENT, ChatMessage.from(queued.copy(status = "sent")).status)
        assertEquals(MessageStatus.DELIVERED, ChatMessage.from(queued.copy(status = "delivered")).status)
        assertEquals(MessageStatus.READ, ChatMessage.from(queued.copy(status = "read")).status)
    }

    private fun directConversation(
        displayName: String,
        publicKey: String,
    ) =
        ChatConversation(
            id = "direct",
            type = ConversationType.DIRECT,
            displayName = displayName,
            participantIds = listOf(publicKey),
        )
}
