// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.filters.MediumTest
import co.electriccoin.zcash.test.UiTestPrerequisites
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.chat.list.ChatListChipVariant
import co.electriccoin.zcash.ui.screen.chat.list.ChatListItemState
import co.electriccoin.zcash.ui.screen.chat.list.ChatListNetworkChipState
import co.electriccoin.zcash.ui.screen.chat.list.ChatListState
import co.electriccoin.zcash.ui.screen.chat.list.ChatListSupportRowState
import co.electriccoin.zcash.ui.screen.chat.view.ChatListView
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertTrue

class ChatListScrollTest : UiTestPrerequisites() {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    @MediumTest
    fun drag_starting_on_a_row_scrolls_to_the_last_conversation() {
        composeTestRule.setContent {
            ZcashTheme {
                ChatListView(state = state(conversationCount = 30), showBackButton = false)
            }
        }

        // Each swipe starts mid-screen, i.e. on a conversation row, which is where the
        // swipe-to-leave gesture lives.
        repeat(SWIPES) {
            composeTestRule.onRoot().performTouchInput { swipeUp(durationMillis = 300) }
        }

        composeTestRule.onNodeWithText("Conversation 29").assertIsDisplayed()
    }

    @Test
    @MediumTest
    fun last_conversation_scrolls_clear_of_the_new_chat_fab() {
        composeTestRule.setContent {
            ZcashTheme {
                ChatListView(state = state(conversationCount = 30), showBackButton = false)
            }
        }

        repeat(SWIPES) {
            composeTestRule.onRoot().performTouchInput { swipeUp(durationMillis = 300) }
        }

        val lastRowBottom = composeTestRule.onNodeWithText("Conversation 29").getBoundsInRoot().bottom
        val fabTop = composeTestRule.onNodeWithContentDescription("New chat").getBoundsInRoot().top
        assertTrue(lastRowBottom <= fabTop, "last row ends at $lastRowBottom, under the FAB starting at $fabTop")
    }

    private fun state(conversationCount: Int) =
        ChatListState(
            title = stringRes("Chats"),
            isLoading = false,
            items =
                List(conversationCount) { index ->
                    ChatListItemState(
                        id = "conversation-$index",
                        displayName = "Conversation $index",
                        isGroup = false,
                        lastMessage = stringRes("Last message"),
                        timeLabel = null,
                        unreadCount = 0,
                        onClick = {},
                        onLeaveSwipe = {},
                    )
                },
            emptyTitle = stringRes(""),
            emptySubtitle = stringRes(""),
            newConversationContentDescription = stringRes("New chat"),
            onBack = {},
            onNewConversationClick = {},
            networkChip = ChatListNetworkChipState(stringRes("Online"), ChatListChipVariant.Success) {},
            networkSheet = null,
            tosDialog = null,
            leaveDialog = null,
            supportRow = ChatListSupportRowState(stringRes("Get help"), totalUnreadCount = 0) {},
        )

    private companion object {
        const val SWIPES = 8
    }
}
