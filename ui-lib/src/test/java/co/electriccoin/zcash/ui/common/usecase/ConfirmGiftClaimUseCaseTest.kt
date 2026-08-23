// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.TransactionId
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.zcash.ui.common.datasource.GiftClaimDataSource
import co.electriccoin.zcash.ui.common.datasource.GiftClaimFinalization
import co.electriccoin.zcash.ui.common.provider.GiftClaimOperationLock
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.common.provider.ReceivedGiftStorageProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.repository.ReceiveTransaction
import co.electriccoin.zcash.ui.common.repository.TransactionRepository
import co.electriccoin.zcash.ui.screen.gift.model.GiftLinkPayload
import co.electriccoin.zcash.ui.screen.gift.model.ReceivedGift
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class ConfirmGiftClaimUseCaseTest {
    @Test
    fun `finalizes durably before cleanup and settlement`() =
        runTest {
            val fixture = Fixture(finalTransaction = true)

            fixture.useCase.reconcile()

            coVerifyOrder {
                fixture.receipts.markFinalized(ADDRESS)
                fixture.dataSource.cleanupFinalizedClaim(PAYLOAD, ADDRESS, ZcashNetwork.Mainnet)
                fixture.receipts.settle(ADDRESS)
            }
        }

    @Test
    fun `does not settle a transaction below finality`() =
        runTest {
            val fixture = Fixture(finalTransaction = false)

            fixture.useCase.reconcile()

            coVerify(exactly = 0) { fixture.receipts.markFinalized(any()) }
            coVerify(exactly = 0) { fixture.receipts.settle(any()) }
            coVerify(exactly = 0) { fixture.dataSource.cleanupFinalizedClaim(any(), any(), any()) }
        }

    private class Fixture(
        finalTransaction: Boolean,
    ) {
        val receipts = mockk<ReceivedGiftStorageProvider>(relaxed = true)
        val dataSource = mockk<GiftClaimDataSource>(relaxed = true)
        private val transactions = mockk<TransactionRepository>(relaxed = true)
        private val synchronizerProvider = mockk<SynchronizerProvider>()
        private val persistableWalletProvider = mockk<PersistableWalletProvider>(relaxed = true)

        val useCase: ConfirmGiftClaimUseCase

        init {
            coEvery { receipts.getAll() } returns listOf(RECEIPT)
            val transaction =
                if (finalTransaction) {
                    mockk<ReceiveTransaction.Success>().also {
                        every { it.id } returns TransactionId.new(ByteArray(32))
                    }
                } else {
                    mockk<ReceiveTransaction.Pending>().also {
                        every { it.id } returns TransactionId.new(ByteArray(32))
                    }
                }
            coEvery { transactions.getTransactions() } returns listOf(transaction)
            coEvery { synchronizerProvider.getSynchronizer() } returns
                mockk<Synchronizer>().also { every { it.network } returns ZcashNetwork.Mainnet }
            coEvery { dataSource.inspectFinalization(any(), any(), any(), any()) } returns
                GiftClaimFinalization(canSettle = true, residual = Zatoshi(0L))

            useCase =
                ConfirmGiftClaimUseCase(
                    receivedGiftStorageProvider = receipts,
                    transactionRepository = transactions,
                    synchronizerProvider = synchronizerProvider,
                    persistableWalletProvider = persistableWalletProvider,
                    giftClaimDataSource = dataSource,
                    giftClaimOperationLock = GiftClaimOperationLock(),
                )
        }
    }

    private companion object {
        const val ADDRESS = "card-address"
        val PAYLOAD =
            GiftLinkPayload(
                v = 1,
                network = "main",
                amountZatoshi = "100000000",
                mnemonic = "test mnemonic",
                birthdayHeight = 1L,
                createdAt = "2026-08-23T00:00:00Z",
            )
        val RECEIPT =
            ReceivedGift(
                address = ADDRESS,
                network = "main",
                amountZatoshi = 100_000_000L,
                claimedAt = "2026-08-23T00:00:00Z",
                claimTxids = listOf(TransactionId.new(ByteArray(32)).txIdString()),
                claimLink = PAYLOAD,
            )
    }
}
