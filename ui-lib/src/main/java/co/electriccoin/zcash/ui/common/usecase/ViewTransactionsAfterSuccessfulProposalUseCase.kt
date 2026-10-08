package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.provider.ChatSendContextProvider
import co.electriccoin.zcash.ui.common.repository.KeystoneProposalRepository
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepository
import co.electriccoin.zcash.ui.screen.chat.ChatRoomArgs

class ViewTransactionsAfterSuccessfulProposalUseCase(
    private val keystoneProposalRepository: KeystoneProposalRepository,
    private val zashiProposalRepository: ZashiProposalRepository,
    private val navigationRouter: NavigationRouter,
    private val prefillSend: PrefillSendUseCase,
    private val chatSendContext: ChatSendContextProvider,
) {
    operator fun invoke() {
        zashiProposalRepository.clear()
        keystoneProposalRepository.clear()
        prefillSend.clear()
        if (chatSendContext.consumeReturnTarget() != null) {
            // A send started from a chat was pushed on top of that chat room, so it is still on
            // the stack: going back to it shows the payment's receipt in the conversation.
            navigationRouter.backTo(ChatRoomArgs::class)
        } else {
            navigationRouter.backToRoot()
        }
    }
}
