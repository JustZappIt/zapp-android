package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.provider.ChatSendContextProvider
import co.electriccoin.zcash.ui.common.repository.SwapRepository
import io.mockk.mockk
import io.mockk.verifyOrder
import kotlin.test.Test
import kotlin.test.assertNull

/**
 * [CancelSwapUseCase] tears down the in-flight swap: it clears the shared swap state and then pops
 * back to the root, in that order. It also drops a pending chat send, whose room that pops.
 */
class CancelSwapUseCaseTest {
    @Test
    fun clearsSwapStateThenReturnsToRoot() {
        val swapRepository = mockk<SwapRepository>(relaxed = true)
        val navigationRouter = mockk<NavigationRouter>(relaxed = true)

        CancelSwapUseCase(swapRepository, navigationRouter, ChatSendContextProvider())()

        verifyOrder {
            swapRepository.clear()
            navigationRouter.backToRoot()
        }
    }

    @Test
    fun dropsAPendingChatSend() {
        val chatSendContext = ChatSendContextProvider()
        chatSendContext.set("conversation")

        CancelSwapUseCase(mockk(relaxed = true), mockk(relaxed = true), chatSendContext)()

        assertNull(chatSendContext.consume())
    }
}
