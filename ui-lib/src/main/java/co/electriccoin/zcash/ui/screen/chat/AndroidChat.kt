// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.screen.chat.common.ChatBootstrap
import co.electriccoin.zcash.ui.screen.chat.contacts.ChatContactsScreen
import co.electriccoin.zcash.ui.screen.chat.identity.ChatIdentitySetupScreen
import co.electriccoin.zcash.ui.screen.chat.identity.ChatIdentitySetupVM
import co.electriccoin.zcash.ui.screen.chat.list.ChatListScreen
import co.electriccoin.zcash.ui.screen.chat.newconv.NewConversationScreen
import co.electriccoin.zcash.ui.screen.chat.profile.ChatProfileScreen
import co.electriccoin.zcash.ui.screen.chat.room.ChatRoomScreen
import co.electriccoin.zcash.ui.screen.chat.settings.ChatSettingsScreen
import co.electriccoin.zcash.ui.screen.chat.support.SupportChatScreen
import co.electriccoin.zcash.ui.screen.chat.support.SupportTicketListScreen
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

// ── Navigation route args ───────────────────────────────────────────────

@Serializable
data object ChatHomeArgs

@Serializable
data class ChatRoomArgs(
    val conversationId: String
)

@Serializable
data object NewConversationArgs

@Serializable
data object ChatContactsArgs

@Serializable
data object ChatProfileArgs

@Serializable
data object ChatSettingsArgs

@Serializable
data object SupportTicketListArgs

@Serializable
data class SupportChatArgs(
    val conversationId: String = "",
)

// ── Entry point composables ─────────────────────────────────────────────

@Composable
fun AndroidChatHome() {
    val bootstrap: ChatBootstrap = koinInject()
    val identitySetupVm: ChatIdentitySetupVM = koinViewModel()
    val isInitializing by bootstrap.isInitializing.collectAsState()
    val isSetupComplete by identitySetupVm.isSetupComplete.collectAsState()

    when {
        isInitializing -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = ZappTheme.colors.accent)
            }
        }

        !isSetupComplete -> {
            ChatIdentitySetupScreen()
        }

        else -> {
            ChatListScreen()
        }
    }
}

@Composable
fun AndroidChatRoom(args: ChatRoomArgs) {
    val bootstrap: ChatBootstrap = koinInject()
    val isInitializing by bootstrap.isInitializing.collectAsState()
    if (isInitializing) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(color = ZappTheme.colors.accent)
        }
    } else {
        ChatRoomScreen(args = args)
    }
}

@Composable
fun AndroidNewConversation() {
    NewConversationScreen()
}

@Composable
fun AndroidChatContacts() {
    ChatContactsScreen()
}

@Composable
fun AndroidChatProfile() {
    ChatProfileScreen()
}

@Composable
fun AndroidChatSettings() {
    ChatSettingsScreen()
}

@Composable
fun AndroidSupportTicketList() {
    SupportTicketListScreen()
}

@Composable
fun AndroidSupportChat(args: SupportChatArgs) {
    SupportChatScreen(args = args)
}
