// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.provider.ChatSendContextProvider
import co.electriccoin.zcash.ui.screen.chat.ChatRoomArgs
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertNull

/**
 * Pins where closing a finished send lands: back in the chat a payment came from, so its receipt
 * is in view, and on the home tab for everything else — including the send after a chat payment.
 */
class ViewTransactionsAfterSuccessfulProposalUseCaseTest {
    private val navigationRouter = mockk<NavigationRouter>(relaxed = true)
    private val chatSendContext = ChatSendContextProvider()
    private val useCase =
        ViewTransactionsAfterSuccessfulProposalUseCase(
            keystoneProposalRepository = mockk(relaxed = true),
            zashiProposalRepository = mockk(relaxed = true),
            navigationRouter = navigationRouter,
            prefillSend = mockk(relaxed = true),
            chatSendContext = chatSendContext,
        )

    @Test
    fun `a send submitted from a chat returns to that chat`() {
        chatSendContext.set("conversation")
        chatSendContext.markSubmitted(chatSendContext.consume()?.conversationId)

        useCase()

        verify(exactly = 1) { navigationRouter.backTo(ChatRoomArgs::class) }
        verify(exactly = 0) { navigationRouter.backToRoot() }
    }

    @Test
    fun `a send that did not start in a chat goes home`() {
        chatSendContext.markSubmitted(chatSendContext.consume()?.conversationId)

        useCase()

        verify(exactly = 1) { navigationRouter.backToRoot() }
        verify(exactly = 0) { navigationRouter.backTo(any()) }
    }

    @Test
    fun `the way back is used once`() {
        chatSendContext.set("conversation")
        chatSendContext.markSubmitted(chatSendContext.consume()?.conversationId)

        useCase()
        assertNull(chatSendContext.consumeReturnTarget())
    }

    @Test
    fun `a later unrelated send is not steered back to an old chat`() {
        chatSendContext.set("conversation")
        chatSendContext.markSubmitted(chatSendContext.consume()?.conversationId)
        // The progress screen was left without closing it, then a send from home was submitted.
        chatSendContext.markSubmitted(chatSendContext.consume()?.conversationId)

        useCase()

        verify(exactly = 1) { navigationRouter.backToRoot() }
    }

    @Test
    fun `starting a new chat send drops an unused way back`() {
        chatSendContext.markSubmitted("old conversation")
        chatSendContext.set("new conversation")

        assertNull(chatSendContext.consumeReturnTarget())
    }
}
