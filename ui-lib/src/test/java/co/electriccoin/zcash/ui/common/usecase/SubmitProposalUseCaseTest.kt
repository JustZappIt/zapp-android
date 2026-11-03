// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.RegularTransactionProposal
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import co.electriccoin.zcash.ui.common.provider.ChatSendContextProvider
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A Zashi send started in a chat returns to that chat only once it lands, since only then does its
 * receipt show there. Anything less closes home, which keeps the retry and support options.
 */
class SubmitProposalUseCaseTest {
    private val accountDataSource = mockk<AccountDataSource>()
    private val zashiProposalRepository = mockk<ZashiProposalRepository>(relaxed = true)
    private val chatSendContext = ChatSendContextProvider()
    private val useCase =
        SubmitProposalUseCase(
            navigationRouter = mockk(relaxed = true),
            accountDataSource = accountDataSource,
            zashiProposalRepository = zashiProposalRepository,
            keystoneProposalRepository = mockk(relaxed = true),
            biometricRepository = mockk(relaxed = true),
            swapRepository = mockk(relaxed = true),
            metadataRepository = mockk(relaxed = true),
            processSwapTransaction = mockk(relaxed = true),
            prefillSend = PrefillSendUseCase(),
            chatSendContext = chatSendContext,
            messagingSDK = mockk(relaxed = true),
        )

    private fun givenSubmitReturns(result: SubmitResult) {
        coEvery { accountDataSource.getSelectedAccount() } returns mockk<ZashiAccount>()
        coEvery { zashiProposalRepository.getTransactionProposal() } returns mockk<RegularTransactionProposal>()
        coEvery { zashiProposalRepository.submit() } returns result
    }

    @Test
    fun `a chat send that lands returns to the chat`() =
        runTest {
            givenSubmitReturns(SubmitResult.Success(txIds = listOf("tx")))
            chatSendContext.set("conversation")

            useCase()

            coVerify(timeout = TIMEOUT_MS) { zashiProposalRepository.submit() }
            assertEquals("conversation", awaitReturnTarget())
        }

    @Test
    fun `a chat send that fails closes home`() =
        runTest {
            givenSubmitReturns(SubmitResult.Failure(txIds = emptyList(), code = 1, description = null))
            chatSendContext.set("conversation")

            useCase()

            coVerify(timeout = TIMEOUT_MS) { zashiProposalRepository.submit() }
            assertNull(chatSendContext.consumeReturnTarget())
        }

    /** The submit finishes on its own scope, after [SubmitProposalUseCase.invoke] returns. */
    private fun awaitReturnTarget(): String? {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            chatSendContext.consumeReturnTarget()?.let { return it }
            Thread.sleep(POLL_MS)
        }
        return null
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val POLL_MS = 10L
    }
}
