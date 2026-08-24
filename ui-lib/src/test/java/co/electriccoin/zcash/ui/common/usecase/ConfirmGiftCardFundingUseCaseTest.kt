// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.model.TransactionId
import cash.z.ecc.android.sdk.model.TransactionOverview
import cash.z.ecc.android.sdk.model.TransactionState
import co.electriccoin.zcash.ui.common.provider.GiftCardStorageProvider
import co.electriccoin.zcash.ui.common.provider.GiftFundingOperationLock
import co.electriccoin.zcash.ui.common.repository.SyncedAccountTransactionSnapshot
import co.electriccoin.zcash.ui.common.repository.TransactionRepository
import co.electriccoin.zcash.ui.screen.gift.model.GiftCardStatus
import co.electriccoin.zcash.ui.screen.gift.model.GiftFundingFailure
import co.electriccoin.zcash.ui.screen.gift.model.GiftFundingFailureReason
import co.electriccoin.zcash.ui.screen.gift.model.StoredGiftCard
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class ConfirmGiftCardFundingUseCaseTest {
    @Test
    fun `a synced missing transaction makes the durable marker retryable`() =
        runTest {
            val fixture = fixture(card(fundingAttemptedAt = NOW))

            fixture.useCase.reconcile()

            coVerify(exactly = 1) { fixture.storage.markFundingNotCreated(ID, any()) }
            coVerify(exactly = 0) { fixture.storage.recordFundingCreated(any(), any(), any()) }
        }

    @Test
    fun `reattaches a pending transaction without claiming it was submitted`() =
        runTest {
            val pending = send(TXID, TransactionState.Pending, expiry = EXPIRY)
            val fixture = fixture(card(fundingAttemptedAt = NOW), snapshot(pending))

            fixture.useCase.reconcile()

            coVerify(exactly = 1) { fixture.storage.recordFundingCreated(ID, TXID, any()) }
            coVerify(exactly = 0) { fixture.storage.recordFundingSubmitted(any(), any(), any()) }
            coVerify(exactly = 0) { fixture.storage.markFundingNotCreated(any(), any()) }
        }

    @Test
    fun `reattaches and confirms a mined transaction`() =
        runTest {
            val confirmed = send(TXID, TransactionState.Confirmed, expiry = EXPIRY, mined = EXPIRY - 5)
            val fixture = fixture(card(fundingAttemptedAt = NOW), snapshot(confirmed))

            fixture.useCase.reconcile()

            coVerify(exactly = 1) { fixture.storage.recordFundingCreated(ID, TXID, any()) }
            coVerify(exactly = 1) { fixture.storage.markFunded(ID, TXID, any()) }
        }

    @Test
    fun `leaves a known pending transaction alone`() =
        runTest {
            val fixture = fixture(card(fundingTxid = TXID), snapshot(send(TXID, TransactionState.Pending, EXPIRY)))

            fixture.useCase.reconcile()

            coVerify(exactly = 0) { fixture.storage.markFundingExpired(any(), any(), any()) }
            coVerify(exactly = 0) { fixture.storage.markFunded(any(), any(), any()) }
        }

    @Test
    fun `confirms a card whose link was shared before funding mined`() =
        runTest {
            val confirmed = send(TXID, TransactionState.Confirmed, expiry = EXPIRY, mined = EXPIRY - 5)
            val fixture = fixture(card(fundingTxid = TXID, status = GiftCardStatus.SHARED), snapshot(confirmed))

            fixture.useCase.reconcile()

            coVerify(exactly = 1) { fixture.storage.markFunded(ID, TXID, any()) }
        }

    @Test
    fun `makes a transaction expired in the synced snapshot retryable`() =
        runTest {
            val expired = send(TXID, TransactionState.Expired, expiry = EXPIRY)
            val fixture = fixture(card(fundingTxid = TXID), snapshot(expired))

            fixture.useCase.reconcile()

            coVerify(exactly = 1) { fixture.storage.markFundingExpired(ID, setOf(TXID), any()) }
        }

    @Test
    fun `an observed expiry is revalidated by a synced snapshot before retry`() =
        runTest {
            val expired = send(TXID, TransactionState.Expired, expiry = EXPIRY)
            val fixture = fixture(card(fundingTxid = TXID), snapshot(expired))
            every { fixture.repository.observeAccountTransaction(ACCOUNT, TXID) } returns flowOf(expired)

            fixture.useCase(ID, TXID)

            coVerify(exactly = 1) { fixture.repository.getSyncedAccountTransactionSnapshot(ACCOUNT) }
            coVerify(exactly = 1) { fixture.storage.markFundingExpired(ID, setOf(TXID), any()) }
        }

    @Test
    fun `a recovered pending transaction stays observed until it confirms`() =
        runTest {
            val pending = send(TXID, TransactionState.Pending, expiry = EXPIRY)
            val confirmed = send(TXID, TransactionState.Confirmed, expiry = EXPIRY, mined = EXPIRY - 5)
            val fixture = fixture(card(fundingTxid = TXID), snapshot(pending))
            every { fixture.repository.observeAccountTransaction(ACCOUNT, TXID) } returns
                flowOf(pending, confirmed)

            fixture.useCase.reconcileAndObserve()

            coVerify(exactly = 1) { fixture.storage.markFunded(ID, TXID, any()) }
        }

    @Test
    fun `a marker-only recovery attaches pending then observes it to confirmation`() =
        runTest {
            var current = card(fundingAttemptedAt = NOW)
            val pending = send(TXID, TransactionState.Pending, expiry = EXPIRY)
            val confirmed = send(TXID, TransactionState.Confirmed, expiry = EXPIRY, mined = EXPIRY - 5)
            val storage = mockk<GiftCardStorageProvider>(relaxed = true)
            coEvery { storage.getAll() } answers { listOf(current) }
            coEvery { storage.get(ID) } answers { current }
            coEvery { storage.recordFundingCreated(ID, TXID, any()) } answers {
                current = current.copy(fundingTxid = TXID, fundingCreatedAt = LATER)
            }
            val repository = mockk<TransactionRepository>()
            coEvery { repository.getSyncedAccountTransactionSnapshot(ACCOUNT) } returns snapshot(pending)
            every { repository.observeAccountTransaction(ACCOUNT, TXID) } returns flowOf(pending, confirmed)
            val useCase = ConfirmGiftCardFundingUseCase(storage, repository, GiftFundingOperationLock())

            useCase.reconcileAndObserve()

            coVerify(exactly = 1) { storage.recordFundingCreated(ID, TXID, any()) }
            coVerify(exactly = 1) { storage.markFunded(ID, TXID, any()) }
        }

    @Test
    fun `known expired attempt fails closed when two replacement candidates are live`() =
        runTest {
            val expired = send(TXID, TransactionState.Expired, expiry = EXPIRY)
            val first = send(OTHER_TXID, TransactionState.Pending, expiry = EXPIRY + 200)
            val second = send(THIRD_TXID, TransactionState.Confirmed, expiry = EXPIRY + 201)
            val fixture = fixture(card(fundingTxid = TXID), snapshot(expired, first, second))

            fixture.useCase.reconcile()

            coVerify(exactly = 0) { fixture.storage.replaceExpiredFunding(any(), any(), any(), any()) }
            coVerify(exactly = 0) { fixture.storage.markFundingExpired(any(), any(), any()) }
            coVerify(exactly = 0) { fixture.storage.markFunded(any(), any(), any()) }
        }

    @Test
    fun `old expired retries stay history-only while the current transaction is pending`() =
        runTest {
            val oldFailure =
                GiftFundingFailure(
                    reason = GiftFundingFailureReason.EXPIRED,
                    attemptedAt = NOW,
                    transactionId = OLD_TXID,
                    detectedAt = LATER,
                )
            val active = send(TXID, TransactionState.Pending, expiry = EXPIRY + 200)
            val old = send(OLD_TXID, TransactionState.Expired, expiry = EXPIRY)
            val fixture =
                fixture(
                    card(fundingTxid = TXID, fundingFailures = listOf(oldFailure)),
                    snapshot(old, active),
                )

            fixture.useCase.reconcile()

            coVerify(exactly = 0) { fixture.storage.replaceExpiredFunding(any(), any(), any(), any()) }
            coVerify(exactly = 0) { fixture.storage.markFundingExpired(any(), any(), any()) }
        }

    @Test
    fun `a crashed retry attaches its one new live transaction and ignores expired history`() =
        runTest {
            val oldFailure =
                GiftFundingFailure(
                    reason = GiftFundingFailureReason.EXPIRED,
                    attemptedAt = NOW,
                    transactionId = OLD_TXID,
                    detectedAt = LATER,
                )
            val active = send(TXID, TransactionState.Pending, expiry = EXPIRY + 200)
            val old = send(OLD_TXID, TransactionState.Expired, expiry = EXPIRY)
            val fixture =
                fixture(
                    card(fundingAttemptedAt = LATER, fundingFailures = listOf(oldFailure)),
                    snapshot(old, active),
                )

            fixture.useCase.reconcile()

            coVerify(exactly = 1) { fixture.storage.recordFundingCreated(ID, TXID, any()) }
        }

    @Test
    fun `fails closed when one attempt has multiple live candidate transactions`() =
        runTest {
            val first = send(TXID, TransactionState.Pending, expiry = EXPIRY)
            val second = send(OTHER_TXID, TransactionState.Pending, expiry = EXPIRY + 1)
            val fixture = fixture(card(fundingAttemptedAt = NOW), snapshot(first, second))

            fixture.useCase.reconcile()

            coVerify(exactly = 0) { fixture.storage.recordFundingCreated(any(), any(), any()) }
            coVerify(exactly = 0) { fixture.storage.markFundingNotCreated(any(), any()) }
            coVerify(exactly = 0) { fixture.storage.markFundingExpired(any(), any(), any()) }
        }

    @Test
    fun `concurrent reconciliation is serialized per card`() =
        runTest {
            var current = card(fundingAttemptedAt = NOW)
            val storage = mockk<GiftCardStorageProvider>(relaxed = true)
            coEvery { storage.getAll() } answers { listOf(current) }
            coEvery { storage.get(ID) } answers { current }
            coEvery { storage.markFundingNotCreated(ID, any()) } answers {
                current = current.copy(fundingAttemptedAt = null, fundingFailures = listOf(noTransactionFailure()))
            }
            val repository = mockk<TransactionRepository>()
            coEvery { repository.getSyncedAccountTransactionSnapshot(ACCOUNT) } returns snapshot()
            val useCase = ConfirmGiftCardFundingUseCase(storage, repository, GiftFundingOperationLock())

            listOf(async { useCase.reconcile() }, async { useCase.reconcile() }).awaitAll()

            coVerify(exactly = 1) { storage.markFundingNotCreated(ID, any()) }
        }

    private fun fixture(
        card: StoredGiftCard,
        snapshot: SyncedAccountTransactionSnapshot = snapshot(),
    ): Fixture {
        val storage = mockk<GiftCardStorageProvider>(relaxed = true)
        coEvery { storage.getAll() } returns listOf(card)
        coEvery { storage.get(ID) } returns card
        val repository = mockk<TransactionRepository>(relaxed = true)
        coEvery { repository.getSyncedAccountTransactionSnapshot(ACCOUNT) } returns snapshot
        return Fixture(
            storage = storage,
            repository = repository,
            useCase = ConfirmGiftCardFundingUseCase(storage, repository, GiftFundingOperationLock()),
        )
    }

    private data class Fixture(
        val storage: GiftCardStorageProvider,
        val repository: TransactionRepository,
        val useCase: ConfirmGiftCardFundingUseCase,
    )

    private fun snapshot(
        vararg transactions: TransactionOverview,
    ) = SyncedAccountTransactionSnapshot(
        transactions = transactions.toList(),
        recipientsByTransactionId = transactions.associate { it.txId.txIdString() to setOf(ADDRESS) },
    )

    private fun send(
        txid: String,
        state: TransactionState,
        expiry: Long,
        mined: Long? = null,
    ) = mockk<TransactionOverview>().also { transaction ->
        every { transaction.txId } returns TransactionId.new(txid)
        every { transaction.isSentTransaction } returns true
        every { transaction.transactionState } returns state
        every { transaction.expiryHeight } returns
            cash.z.ecc.android.sdk.model.BlockHeight
                .new(expiry)
        every { transaction.minedHeight } returns
            mined?.let {
                cash.z.ecc.android.sdk.model.BlockHeight
                    .new(it)
            }
        every { transaction.index } returns null
    }

    private fun card(
        fundingTxid: String? = null,
        fundingAttemptedAt: String? = null,
        fundingFailures: List<GiftFundingFailure> = emptyList(),
        status: GiftCardStatus = GiftCardStatus.DRAFT,
    ) = StoredGiftCard(
        id = ID,
        network = "main",
        address = ADDRESS,
        mnemonic = MNEMONIC,
        amountZatoshi = 100_000_000L,
        birthdayHeight = 2_800_000L,
        sourceAccountUuid = ACCOUNT,
        createdAt = NOW,
        updatedAt = NOW,
        status = status,
        fundingTxid = fundingTxid,
        fundingAttemptedAt = fundingAttemptedAt,
        fundingFailures = fundingFailures,
    )

    private fun noTransactionFailure() =
        GiftFundingFailure(
            reason = GiftFundingFailureReason.NO_TRANSACTION,
            attemptedAt = NOW,
            detectedAt = LATER,
        )

    private companion object {
        const val ID = "card-1"
        const val ACCOUNT = "account-1"
        const val ADDRESS = "u1exampleunifiedaddressforgiftcardtests"
        const val TXID = "f00d"
        const val OLD_TXID = "cafe"
        const val OTHER_TXID = "beef"
        const val THIRD_TXID = "babe"
        const val EXPIRY = 2_900_000L
        const val NOW = "2026-08-20T12:00:00Z"
        const val LATER = "2026-08-20T12:05:00Z"

        /** BIP-39 test vector for all-zero entropy. Never a real wallet. */
        const val MNEMONIC =
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon art"
    }
}
