package co.electriccoin.zcash.ui

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import co.electriccoin.zcash.ui.screen.chat.AndroidChatContacts
import co.electriccoin.zcash.ui.screen.chat.AndroidChatHome
import co.electriccoin.zcash.ui.screen.chat.AndroidChatProfile
import co.electriccoin.zcash.ui.screen.chat.AndroidChatRoom
import co.electriccoin.zcash.ui.screen.chat.AndroidChatSettings
import co.electriccoin.zcash.ui.screen.chat.AndroidNewConversation
import co.electriccoin.zcash.ui.screen.chat.AndroidSupportChat
import co.electriccoin.zcash.ui.screen.chat.AndroidSupportTicketList
import co.electriccoin.zcash.ui.screen.chat.ChatContactsArgs
import co.electriccoin.zcash.ui.screen.chat.ChatHomeArgs
import co.electriccoin.zcash.ui.screen.chat.ChatProfileArgs
import co.electriccoin.zcash.ui.screen.chat.ChatRoomArgs
import co.electriccoin.zcash.ui.screen.chat.ChatSettingsArgs
import co.electriccoin.zcash.ui.screen.chat.NewConversationArgs
import co.electriccoin.zcash.ui.screen.chat.SupportChatArgs
import co.electriccoin.zcash.ui.screen.chat.SupportTicketListArgs
import co.electriccoin.zcash.ui.screen.chat.p2pkey.ChatP2pKeyArgs
import co.electriccoin.zcash.ui.screen.chat.p2pkey.ChatP2pKeyScreen
import co.electriccoin.zcash.ui.screen.chat.scan.ChatScanPublicKeyArgs
import co.electriccoin.zcash.ui.screen.chat.scan.ChatScanPublicKeyScreen
import co.electriccoin.zcash.ui.screen.chat.walletaddress.ChatWalletAddressArgs
import co.electriccoin.zcash.ui.screen.chat.walletaddress.ChatWalletAddressScreen

/**
 * Registers the P2P chat (Zapp Messaging) screens.
 *
 * Chat is a sibling sub-graph of the wallet graph:
 * `RootNavGraph` -> `WalletNavGraph` + `ChatNavGraph`. Today
 * the chat screens are nested inside `MainAppGraph` for back-stack continuity
 * with the tabs shell, but isolating them in this extension function keeps
 * the wallet graph focused on wallet flows and lets us promote chat to a
 * top-level `navigation<ChatGraph>` block later without touching wallet code.
 */
fun NavGraphBuilder.chatNavGraph(navigationRouter: NavigationRouter) {
    composable<ChatHomeArgs> {
        AndroidChatHome()
    }
    composable<ChatRoomArgs> { backStackEntry ->
        AndroidChatRoom(args = backStackEntry.toRoute())
    }
    composable<NewConversationArgs> {
        AndroidNewConversation()
    }
    composable<ChatContactsArgs> {
        AndroidChatContacts()
    }
    composable<ChatProfileArgs> {
        AndroidChatProfile()
    }
    composable<ChatSettingsArgs> {
        AndroidChatSettings()
    }
    composable<ChatWalletAddressArgs> {
        ChatWalletAddressScreen()
    }
    composable<ChatP2pKeyArgs> {
        ChatP2pKeyScreen()
    }
    composable<ChatScanPublicKeyArgs> { backStackEntry ->
        ChatScanPublicKeyScreen(args = backStackEntry.toRoute())
    }
    composable<SupportTicketListArgs> {
        AndroidSupportTicketList()
    }
    composable<SupportChatArgs> { backStackEntry ->
        AndroidSupportChat(args = backStackEntry.toRoute())
    }
}
