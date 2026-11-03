package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.NavigationCommand
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
        val conversationId = chatSendContext.consumeReturnTarget()
        if (conversationId != null) {
            // Back to the exact chat the payment's receipt went to. Home when that room has left
            // the stack, so Close is never a no-op that strands the progress screen.
            navigationRouter.custom { NavigationCommand.BackToOrRoot(ChatRoomArgs(conversationId)) }
        } else {
            navigationRouter.backToRoot()
        }
    }
}
