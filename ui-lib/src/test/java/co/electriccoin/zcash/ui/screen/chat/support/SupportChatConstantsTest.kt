// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.support

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SupportChatConstantsTest {
    @Test
    fun `ordinary group containing the support account remains in the chat list`() {
        assertFalse(
            SupportChatConstants.isSupportConversation(
                displayName = "testing",
                participantIds = listOf(SupportChatConstants.SUPPORT_PUBLIC_KEY, "another-member"),
                localPublicKey = "group-owner",
            ),
        )
    }

    @Test
    fun `ticket with the support account is recognized on the user device`() {
        assertTrue(
            SupportChatConstants.isSupportConversation(
                displayName = "Support: Problem",
                participantIds = listOf(SupportChatConstants.SUPPORT_PUBLIC_KEY),
                localPublicKey = "ticket-owner",
            ),
        )
    }

    @Test
    fun `support prefix alone does not recognize a ticket on the user device`() {
        assertFalse(
            SupportChatConstants.isSupportConversation(
                displayName = "Support: Problem",
                participantIds = listOf("another-member"),
                localPublicKey = "group-owner",
            ),
        )
    }

    @Test
    fun `agent recognizes tickets without their own key in the participant list`() {
        assertTrue(
            SupportChatConstants.isSupportConversation(
                displayName = "Support: Feedback",
                participantIds = listOf("ticket-owner"),
                localPublicKey = SupportChatConstants.SUPPORT_PUBLIC_KEY,
            ),
        )
    }

    @Test
    fun `ordinary group remains in the chat list on the agent device`() {
        assertFalse(
            SupportChatConstants.isSupportConversation(
                displayName = "testing",
                participantIds = listOf("group-owner"),
                localPublicKey = SupportChatConstants.SUPPORT_PUBLIC_KEY,
            ),
        )
    }
}
