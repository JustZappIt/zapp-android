// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.usecase

import androidx.navigation.NavBackStackEntry
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.provider.ChatSendContextProvider
import co.electriccoin.zcash.ui.screen.chat.ChatRoomArgs
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins where closing a finished send lands: back in the exact chat a payment came from, so its
 * receipt is in view (or home when that room has left the stack), and on the home tab for
 * everything else — including the send after a chat payment.
 */
class ViewTransactionsAfterSuccessfulProposalUseCaseTest {
    private val navigationRouter = mockk<NavigationRouter>(relaxed = true)
    private val chatSendContext = ChatSendContextProvider()
    private val useCase =
        ViewTransactionsAfterSuccessfulProposalUseCase(
            keystoneProposalRepository = mockk(relaxed = true),
            zashiProposalRepository = mockk(relaxed = true),
            navigationRouter = navigationRouter,
            prefillSend = PrefillSendUseCase(),
            chatSendContext = chatSendContext,
        )

    @Test
    fun `a chat send that landed returns to that exact chat`() {
        chatSendContext.markSubmitted("conversation")

        useCase()

        val command = slot<(NavBackStackEntry?) -> NavigationCommand?>()
        verify(exactly = 1) { navigationRouter.custom(capture(command)) }
        assertEquals(NavigationCommand.BackToOrRoot(ChatRoomArgs("conversation")), command.captured(null))
        verify(exactly = 0) { navigationRouter.backToRoot() }
    }

    @Test
    fun `a send that did not land from a chat goes home`() {
        chatSendContext.markSubmitted(null)

        useCase()

        verify(exactly = 1) { navigationRouter.backToRoot() }
        verify(exactly = 0) { navigationRouter.custom(any()) }
    }

    @Test
    fun `the way back is used once`() {
        chatSendContext.markSubmitted("conversation")

        useCase()
        assertNull(chatSendContext.consumeReturnTarget())
    }

    @Test
    fun `a later unrelated send is not steered back to an old chat`() {
        chatSendContext.markSubmitted("conversation")
        // The progress screen was left without closing it, then a send from home was submitted.
        chatSendContext.markSubmitted(null)

        useCase()

        verify(exactly = 1) { navigationRouter.backToRoot() }
    }

    @Test
    fun `backing out of a chat send leaves nothing for an unrelated send to pick up`() {
        chatSendContext.set("conversation")
        // Back from the Send screen opened in the chat.
        CancelSwapUseCase(mockk(relaxed = true), navigationRouter, chatSendContext)()

        // What an unrelated send from home then finds when it is submitted.
        assertNull(chatSendContext.consume())
        chatSendContext.markSubmitted(null)
        useCase()

        verify(exactly = 2) { navigationRouter.backToRoot() }
        verify(exactly = 0) { navigationRouter.custom(any()) }
    }

    @Test
    fun `leaving a chat payment through View transaction drops its way back`() {
        chatSendContext.markSubmitted("conversation")

        ViewTransactionDetailAfterSuccessfulProposalUseCase(
            keystoneProposalRepository = mockk(relaxed = true),
            zashiProposalRepository = mockk(relaxed = true),
            navigationRouter = navigationRouter,
            chatSendContext = chatSendContext,
        )("tx")

        assertNull(chatSendContext.consumeReturnTarget())
    }

    @Test
    fun `starting a new chat send drops an unused way back`() {
        chatSendContext.markSubmitted("old conversation")
        chatSendContext.set("new conversation")

        assertNull(chatSendContext.consumeReturnTarget())
    }
}
