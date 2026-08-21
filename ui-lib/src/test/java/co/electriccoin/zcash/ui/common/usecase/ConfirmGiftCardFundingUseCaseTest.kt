// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.model.TransactionId
import co.electriccoin.zcash.ui.common.provider.GiftCardStorageProvider
import co.electriccoin.zcash.ui.common.repository.SendTransaction
import co.electriccoin.zcash.ui.common.repository.Transaction
import co.electriccoin.zcash.ui.common.repository.TransactionRepository
import co.electriccoin.zcash.ui.screen.gift.model.GiftCardStatus
import co.electriccoin.zcash.ui.screen.gift.model.StoredGiftCard
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * Funding is broadcast before its txid can exist, so a process killed in between leaves a record
 * that knows only that an attempt was made. Reattaching that attempt to the transaction it actually
 * produced is what stops the card reading as unfunded while its money sits on chain.
 */
class ConfirmGiftCardFundingUseCaseTest {
    @Test
    fun `reattaches an unresolved broadcast to the transaction it produced`() =
        runTest {
            val storage = storage(card(fundingAttemptedAt = NOW))
            val useCase = useCase(storage, listOf(send(TXID, recipient = ADDRESS, mined = true)))

            useCase.reconcile()

            // The card's address is single-use, so a send to it is that broadcast and nothing else.
            coVerify(exactly = 1) { storage.recordFundingSubmitted(id = ID, fundingTxid = TXID, at = any()) }
            coVerify(exactly = 1) { storage.markFunded(id = ID, fundingTxid = TXID, at = any()) }
        }

    @Test
    fun `records an unresolved broadcast that has not mined yet without marking it funded`() =
        runTest {
            val storage = storage(card(fundingAttemptedAt = NOW))
            val useCase = useCase(storage, listOf(send(TXID, recipient = ADDRESS, mined = false)))

            useCase.reconcile()

            coVerify(exactly = 1) { storage.recordFundingSubmitted(id = ID, fundingTxid = TXID, at = any()) }
            coVerify(exactly = 0) { storage.markFunded(any(), any(), any()) }
        }

    @Test
    fun `leaves an unresolved broadcast alone when nothing reached the card`() =
        runTest {
            val storage = storage(card(fundingAttemptedAt = NOW))
            val useCase = useCase(storage, listOf(send(TXID, recipient = "u1someoneelse", mined = true)))

            useCase.reconcile()

            coVerify(exactly = 0) { storage.recordFundingSubmitted(any(), any(), any()) }
            coVerify(exactly = 0) { storage.markFunded(any(), any(), any()) }
        }

    @Test
    fun `still confirms a card that already carries its txid`() =
        runTest {
            val storage = storage(card(fundingTxid = TXID))
            val useCase = useCase(storage, listOf(send(TXID, recipient = ADDRESS, mined = true)))

            useCase.reconcile()

            coVerify(exactly = 1) { storage.markFunded(id = ID, fundingTxid = TXID, at = any()) }
        }

    private fun storage(vararg cards: StoredGiftCard) =
        mockk<GiftCardStorageProvider>(relaxed = true).also { coEvery { it.getAll() } returns cards.toList() }

    private fun useCase(storage: GiftCardStorageProvider, transactions: List<Transaction>) =
        ConfirmGiftCardFundingUseCase(
            giftCardStorageProvider = storage,
            transactionRepository =
                mockk<TransactionRepository>(relaxed = true).also {
                    coEvery { it.getTransactions() } returns transactions
                },
        )

    private fun send(txid: String, recipient: String, mined: Boolean): Transaction =
        if (mined) {
            mockk<SendTransaction.Success>(relaxed = true)
        } else {
            mockk<SendTransaction.Pending>(relaxed = true)
        }.also {
            coEvery { it.id } returns TransactionId.new(txid)
            coEvery { it.recipient } returns recipient
        }

    private fun card(
        fundingTxid: String? = null,
        fundingAttemptedAt: String? = null,
    ) = StoredGiftCard(
        id = ID,
        network = "main",
        address = ADDRESS,
        mnemonic = MNEMONIC,
        amountZatoshi = 100_000_000L,
        birthdayHeight = 2_800_000L,
        sourceAccountUuid = "account-1",
        createdAt = NOW,
        updatedAt = NOW,
        status = GiftCardStatus.DRAFT,
        fundingTxid = fundingTxid,
        fundingAttemptedAt = fundingAttemptedAt,
    )

    private companion object {
        const val ID = "card-1"
        const val ADDRESS = "u1exampleunifiedaddressforgiftcardtests"
        const val TXID = "f00d"
        const val NOW = "2026-08-20T12:00:00Z"

        /** BIP-39 test vector for all-zero entropy. Never a real wallet. */
        const val MNEMONIC =
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon art"
    }
}
