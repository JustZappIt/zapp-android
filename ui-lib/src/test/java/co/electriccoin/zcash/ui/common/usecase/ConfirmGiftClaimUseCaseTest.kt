// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.TransactionId
import cash.z.ecc.android.sdk.model.TransactionOverview
import cash.z.ecc.android.sdk.model.TransactionState
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.GiftClaimDataSource
import co.electriccoin.zcash.ui.common.datasource.GiftClaimFinalization
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.provider.GiftClaimOperationLock
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.common.provider.ReceivedGiftStorageProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.repository.TransactionRepository
import co.electriccoin.zcash.ui.screen.gift.model.GiftLinkPayload
import co.electriccoin.zcash.ui.screen.gift.model.ReceivedGift
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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

    @Test
    fun `legacy receipt without an account searches every wallet account`() =
        runTest {
            val fixture = Fixture(finalTransaction = true, receipt = RECEIPT.copy(destinationAccountUuid = null))

            fixture.useCase.reconcile()

            coVerify(exactly = 2) { fixture.transactions.getAccountTransactions(ACCOUNT_ID) }
            coVerify(exactly = 1) { fixture.receipts.settle(ADDRESS) }
        }

    @Test
    fun `receipt whose account was reimported under a new uuid searches current accounts`() =
        runTest {
            val fixture =
                Fixture(
                    finalTransaction = true,
                    receipt = RECEIPT.copy(destinationAccountUuid = "removed-account-uuid"),
                )

            fixture.useCase.reconcile()

            coVerify(exactly = 2) { fixture.transactions.getAccountTransactions(ACCOUNT_ID) }
            coVerify(exactly = 1) { fixture.receipts.settle(ADDRESS) }
        }

    @Test
    fun `does not finalize a replacement transaction from a stale reconciliation snapshot`() =
        runTest {
            val fixture = Fixture(finalTransaction = true)
            val replacement =
                RECEIPT.copy(
                    claimTxids = listOf("replacement-txid"),
                    claimSubmissionAttemptedAt = "replacement-attempt",
                )
            coEvery { fixture.receipts.getAll() } returnsMany
                listOf(listOf(RECEIPT), listOf(replacement))

            fixture.useCase.reconcile()

            coVerify(exactly = 0) { fixture.receipts.markFinalized(any()) }
            coVerify(exactly = 0) { fixture.receipts.settle(any()) }
            coVerify(exactly = 0) { fixture.dataSource.cleanupFinalizedClaim(any(), any(), any()) }
        }

    @Test
    fun `reports no confirmations for a receipt that has no claim transaction`() =
        runTest {
            // The guard on the empty combine: a flow built over an empty list never emits, so a
            // screen waiting on it would sit on a bar that can never move.
            val fixture = Fixture(finalTransaction = false, receipt = RECEIPT.copy(claimTxids = emptyList()))

            assertNull(fixture.useCase.observeClaimConfirmations(ADDRESS).first())
        }

    @Test
    fun `counts confirmations from the least-confirmed claim transaction`() =
        runTest {
            // Two transactions, mined four blocks apart. The wait is on the shallower one.
            val fixture =
                Fixture(
                    finalTransaction = false,
                    receipt = RECEIPT.copy(claimTxids = listOf(TXID_A, TXID_B)),
                    minedHeights = mapOf(TXID_A to 100L, TXID_B to 104L),
                    tipHeight = 106L,
                )

            assertEquals(3, fixture.useCase.observeClaimConfirmations(ADDRESS).first())
        }

    @Test
    fun `reports no confirmations while any claim transaction is still unmined`() =
        runTest {
            // The claim is broadcast by the card's own wallet, so this one only learns of it when it
            // mines. A partial count would read as progress on a claim that may never land.
            val fixture =
                Fixture(
                    finalTransaction = false,
                    receipt = RECEIPT.copy(claimTxids = listOf(TXID_A, TXID_B)),
                    minedHeights = mapOf(TXID_A to 100L),
                    tipHeight = 106L,
                )

            assertNull(fixture.useCase.observeClaimConfirmations(ADDRESS).first())
        }

    private class Fixture(
        finalTransaction: Boolean,
        receipt: ReceivedGift = RECEIPT,
        minedHeights: Map<String, Long> = emptyMap(),
        tipHeight: Long? = null,
    ) {
        val receipts = mockk<ReceivedGiftStorageProvider>(relaxed = true)
        val dataSource = mockk<GiftClaimDataSource>(relaxed = true)
        val transactions = mockk<TransactionRepository>(relaxed = true)
        private val accountDataSource = mockk<AccountDataSource>()
        private val synchronizerProvider = mockk<SynchronizerProvider>()
        private val persistableWalletProvider = mockk<PersistableWalletProvider>(relaxed = true)

        val useCase: ConfirmGiftClaimUseCase

        init {
            coEvery { receipts.getAll() } returns listOf(receipt)
            val transaction = mockk<TransactionOverview>()
            every { transaction.txId } returns TransactionId.new(ByteArray(32))
            every { transaction.isSentTransaction } returns false
            every { transaction.transactionState } returns
                if (finalTransaction) TransactionState.Confirmed else TransactionState.Pending
            coEvery { transactions.getAccountTransactions(ACCOUNT_ID) } returns listOf(transaction)
            val sdkAccount = mockk<Account>()
            every { sdkAccount.accountUuid } returns AccountUuid.new(ByteArray(16))
            val walletAccount = mockk<WalletAccount>()
            every { walletAccount.sdkAccount } returns sdkAccount
            coEvery { accountDataSource.getAllAccounts() } returns listOf(walletAccount)
            coEvery { synchronizerProvider.getSynchronizer() } returns
                mockk<Synchronizer>().also { synchronizer ->
                    every { synchronizer.network } returns ZcashNetwork.Mainnet
                    every { synchronizer.networkHeight } returns
                        MutableStateFlow(tipHeight?.let(BlockHeight::new))
                }
            minedHeights.keys.forEach { txid ->
                every { transactions.observeAccountTransaction(ACCOUNT_ID, txid) } returns
                    flowOf(minedTransaction(txid, minedHeights.getValue(txid)))
            }
            receipt.claimTxids.filterNot { it in minedHeights }.forEach { txid ->
                every { transactions.observeAccountTransaction(ACCOUNT_ID, txid) } returns flowOf(null)
            }
            coEvery { dataSource.inspectFinalization(any(), any(), any(), any()) } returns
                GiftClaimFinalization(canSettle = true, residual = Zatoshi(0L))

            useCase =
                ConfirmGiftClaimUseCase(
                    receivedGiftStorageProvider = receipts,
                    transactionRepository = transactions,
                    accountDataSource = accountDataSource,
                    synchronizerProvider = synchronizerProvider,
                    persistableWalletProvider = persistableWalletProvider,
                    giftClaimDataSource = dataSource,
                    giftClaimOperationLock = GiftClaimOperationLock(),
                )
        }
    }

    private companion object {
        fun minedTransaction(txid: String, minedHeight: Long): TransactionOverview =
            mockk<TransactionOverview>().also {
                every { it.txId } returns TransactionId.new(txid.hexToByteArray())
                every { it.minedHeight } returns BlockHeight.new(minedHeight)
            }

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
                destinationAccountUuid = ACCOUNT_ID,
                claimTxids = listOf(TransactionId.new(ByteArray(32)).txIdString()),
                claimLink = PAYLOAD,
            )

        const val ACCOUNT_ID = "00000000000000000000000000000000"

        val TXID_A = "aa".repeat(32)
        val TXID_B = "bb".repeat(32)
    }
}
