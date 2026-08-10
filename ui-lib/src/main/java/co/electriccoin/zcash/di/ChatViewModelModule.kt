package co.electriccoin.zcash.di

import co.electriccoin.zcash.ui.screen.chat.ChatRoomArgs
import co.electriccoin.zcash.ui.screen.chat.contacts.ChatContactsVM
import co.electriccoin.zcash.ui.screen.chat.identity.ChatIdentitySetupVM
import co.electriccoin.zcash.ui.screen.chat.list.ChatListVM
import co.electriccoin.zcash.ui.screen.chat.newconv.NewConversationVM
import co.electriccoin.zcash.ui.screen.chat.p2pkey.ChatP2pKeyVM
import co.electriccoin.zcash.ui.screen.chat.profile.ChatProfileVM
import co.electriccoin.zcash.ui.screen.chat.room.ChatRoomVM
import co.electriccoin.zcash.ui.screen.chat.scan.ChatScanPublicKeyVM
import co.electriccoin.zcash.ui.screen.chat.settings.ChatSettingsVM
import co.electriccoin.zcash.ui.screen.chat.support.SupportChatVM
import co.electriccoin.zcash.ui.screen.chat.support.SupportTicketListVM
import co.electriccoin.zcash.ui.screen.chat.walletaddress.ChatWalletAddressVM
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val chatViewModelModule =
    module {
        viewModelOf(::ChatListVM)
        viewModelOf(::ChatIdentitySetupVM)
        viewModelOf(::ChatProfileVM)
        viewModelOf(::ChatWalletAddressVM)
        viewModelOf(::ChatP2pKeyVM)
        viewModelOf(::ChatSettingsVM)
        viewModelOf(::ChatContactsVM)
        viewModelOf(::NewConversationVM)
        // ChatRoomVM is constructed manually because it takes a runtime [ChatRoomArgs] parameter.
        viewModel { (args: ChatRoomArgs) ->
            ChatRoomVM(
                args = args,
                application = get(),
                chatBootstrap = get(),
                chatContactsRepository = get(),
                chatConversationsRepository = get(),
                transactionRepository = get(),
                getZashiAccount = get(),
                chatSendContext = get(),
                navigationRouter = get(),
                standardPreferenceProvider = get(),
                observeChatMessageReceived = get(),
                observeChatMessageStatus = get(),
                observeChatMediaDownloadComplete = get(),
                observeChatMediaTransferProgress = get(),
                observeChatPeerStatus = get(),
                getChatMessages = get(),
                getChatContacts = get(),
                sendChatMessage = get(),
                sendChatMediaMessage = get(),
                getChatConnectionDetails = get(),
                navigateToScanGenericAddress = get(),
                validateAddress = get(),
                renameChatGroup = get(),
                addChatGroupMember = get(),
                prefillSend = get(),
                exchangeRateRepository = get(),
                copyToClipboard = get(),
            )
        }
        viewModelOf(::ChatScanPublicKeyVM)
        viewModelOf(::SupportTicketListVM)
        viewModelOf(::SupportChatVM)
    }
