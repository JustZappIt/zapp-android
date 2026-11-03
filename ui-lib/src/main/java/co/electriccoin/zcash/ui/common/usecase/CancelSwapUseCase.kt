package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.provider.ChatSendContextProvider
import co.electriccoin.zcash.ui.common.repository.SwapRepository

class CancelSwapUseCase(
    private val swapRepository: SwapRepository,
    private val navigationRouter: NavigationRouter,
    private val chatSendContext: ChatSendContextProvider,
) {
    operator fun invoke() {
        swapRepository.clear()
        // Leaving Send pops the chat it was opened from, so a pending chat send is abandoned too:
        // a stale latch would post the next unrelated send's receipt to that chat.
        chatSendContext.clear()
        navigationRouter.backToRoot()
    }
}
