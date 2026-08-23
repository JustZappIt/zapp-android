// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.common.provider.GiftCardStorageProvider
import co.electriccoin.zcash.ui.common.provider.ReceivedGiftStorageProvider
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

class EnsureNoUnsharedGiftFundsUseCaseTest {
    @Test
    fun `blocks unsettled received gifts`() =
        runTest {
            val useCase = useCase(senderBlocked = false, recipientBlocked = true)

            assertFailsWith<UnsharedGiftFundsException> { useCase() }
        }

    @Test
    fun `blocks unreadable received gift store`() =
        runTest {
            val sender = mockk<GiftCardStorageProvider>()
            val recipient = mockk<ReceivedGiftStorageProvider>()
            coEvery { sender.hasUnsharedFunds() } returns false
            coEvery { recipient.hasUnsettledClaims() } throws IllegalStateException("corrupt")

            assertFailsWith<UnsharedGiftFundsException> { EnsureNoUnsharedGiftFundsUseCase(sender, recipient)() }
        }

    private fun useCase(senderBlocked: Boolean, recipientBlocked: Boolean): EnsureNoUnsharedGiftFundsUseCase {
        val sender = mockk<GiftCardStorageProvider>()
        val recipient = mockk<ReceivedGiftStorageProvider>()
        coEvery { sender.hasUnsharedFunds() } returns senderBlocked
        coEvery { recipient.hasUnsettledClaims() } returns recipientBlocked
        return EnsureNoUnsharedGiftFundsUseCase(sender, recipient)
    }
}
