package co.electriccoin.zcash.ui.screen.chat.support

import co.electriccoin.zcash.ui.screen.chat.model.ConversationType
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SupportChatConstantsTest {
    private val agentKey = SupportChatConstants.SUPPORT_PUBLIC_KEY
    private val userKey = "a".repeat(64)

    @Test
    fun `user side treats the prefixed group with the support key as a ticket`() {
        assertTrue(
            SupportChatConstants.isSupportConversation(
                type = ConversationType.GROUP,
                displayName = "Support: Problem",
                participantIds = listOf(agentKey),
                localPublicKey = userKey,
            )
        )
    }

    @Test
    fun `user side rejects a prefixed group without the support key`() {
        assertFalse(
            SupportChatConstants.isSupportConversation(
                type = ConversationType.GROUP,
                displayName = "Support: Problem",
                participantIds = listOf("b".repeat(64)),
                localPublicKey = userKey,
            )
        )
    }

    @Test
    fun `an ordinary group containing the support key is not a ticket`() {
        assertFalse(
            SupportChatConstants.isSupportConversation(
                type = ConversationType.GROUP,
                displayName = "anything",
                participantIds = listOf(agentKey),
                localPublicKey = userKey,
            )
        )
    }

    @Test
    fun `a direct chat with the support key is never a ticket`() {
        assertFalse(
            SupportChatConstants.isSupportConversation(
                type = ConversationType.DIRECT,
                displayName = "Zapp Support",
                participantIds = listOf(agentKey),
                localPublicKey = userKey,
            )
        )
        assertFalse(
            SupportChatConstants.isSupportConversation(
                type = ConversationType.DIRECT,
                displayName = "Support: Problem",
                participantIds = listOf(agentKey),
                localPublicKey = userKey,
            )
        )
        assertFalse(
            SupportChatConstants.isSupportConversation(
                type = ConversationType.DIRECT,
                displayName = "Support: Problem",
                participantIds = listOf(userKey),
                localPublicKey = agentKey,
            )
        )
    }

    @Test
    fun `support agent side falls back to the display name prefix`() {
        assertTrue(
            SupportChatConstants.isSupportConversation(
                type = ConversationType.GROUP,
                displayName = "Support: Feedback",
                participantIds = listOf(userKey),
                localPublicKey = agentKey,
            )
        )
        assertFalse(
            SupportChatConstants.isSupportConversation(
                type = ConversationType.GROUP,
                displayName = "chinmay",
                participantIds = listOf(userKey),
                localPublicKey = agentKey,
            )
        )
    }
}
