// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.CreatedTransaction
import cash.z.ecc.android.sdk.model.TransactionId
import cash.z.ecc.android.sdk.model.TransactionOverview
import cash.z.ecc.android.sdk.model.TransactionState
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.datasource.RegularTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.ZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.toStorageKeyId
import co.electriccoin.zcash.ui.common.provider.GiftCardStorageProvider
import co.electriccoin.zcash.ui.common.provider.GiftFundingOperationLock
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.common.repository.SyncedAccountTransactionSnapshot
import co.electriccoin.zcash.ui.common.repository.TransactionRepository
import co.electriccoin.zcash.ui.screen.gift.model.GiftCardStatus
import co.electriccoin.zcash.ui.screen.gift.model.GiftFundingFailure
import co.electriccoin.zcash.ui.screen.gift.model.GiftFundingFailureReason
import co.electriccoin.zcash.ui.screen.gift.model.StoredGiftCard
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The broadcast divides [FundGiftCardUseCase.submit]. Everything after it — the storage writes
 * included — has to report as uncertain, because a card has no reclaim and telling the sender
 * nothing was funded while the transaction sits in the mempool invites a second card funded from
 * money that only exists once.
 */
class FundGiftCardUseCaseTest {
    @Test
    fun `returns the txid on a clean submit`() =
        runTest {
            val fixture = Fixture(submitResult = SubmitResult.Success(listOf(TXID)))

            assertEquals(TXID, fixture.useCase.submit(fixture.quote))

            coVerifyOrder {
                fixture.storage.setFundingAttemptedAt(ID, any())
                fixture.proposalDataSource.createTransactions(any(), any())
                fixture.storage.recordFundingCreated(ID, TXID, any())
                fixture.proposalDataSource.submitTransaction(transaction = any(), endpoint = any())
                fixture.storage.recordFundingSubmitted(id = ID, fundingTxid = TXID, at = any())
            }
        }

    @Test
    fun `reports a storage failure after the broadcast as uncertain`() =
        runTest {
            val fixture = Fixture(submitResult = SubmitResult.Success(listOf(TXID)))
            coEvery {
                fixture.storage.recordFundingSubmitted(any(), any(), any())
            } throws IllegalStateException("store is full")

            val thrown = assertFailsWith<GiftFundingException> { fixture.useCase.submit(fixture.quote) }

            assertEquals(GiftFundingError.SUBMIT_UNCERTAIN, thrown.error)
        }

    @Test
    fun `refuses creation when the durable start marker cannot be saved`() =
        runTest {
            val fixture = Fixture(submitResult = SubmitResult.Success(listOf(TXID)))
            coEvery {
                fixture.storage.setFundingAttemptedAt(any(), any())
            } throws IllegalStateException("store is full")

            val thrown = assertFailsWith<GiftFundingException> { fixture.useCase.submit(fixture.quote) }

            assertEquals(GiftFundingError.PROPOSAL_FAILED, thrown.error)
            coVerify(exactly = 0) { fixture.proposalDataSource.createTransactions(any(), any()) }
            coVerify(exactly = 0) {
                fixture.proposalDataSource.submitTransaction(transaction = any(), endpoint = any())
            }
        }

    @Test
    fun `keeps a transaction creation failure unresolved after the durable marker`() =
        runTest {
            val fixture = Fixture(submitResult = SubmitResult.Success(listOf(TXID)))
            coEvery { fixture.proposalDataSource.createTransactions(any(), any()) } throws
                IllegalStateException("proving parameters missing")

            val thrown = assertFailsWith<GiftFundingException> { fixture.useCase.submit(fixture.quote) }

            assertEquals(GiftFundingError.SUBMIT_UNCERTAIN, thrown.error)
            coVerify(exactly = 1) { fixture.storage.setFundingAttemptedAt(ID, any()) }
            coVerify(exactly = 0) { fixture.storage.recordFundingCreated(any(), any(), any()) }
            coVerify(exactly = 0) {
                fixture.proposalDataSource.submitTransaction(transaction = any(), endpoint = any())
            }
        }

    @Test
    fun `keeps the durable marker when the created txid cannot be recorded`() =
        runTest {
            val fixture = Fixture(submitResult = SubmitResult.Success(listOf(TXID)))
            coEvery { fixture.storage.recordFundingCreated(any(), any(), any()) } throws
                IllegalStateException("store is full")

            val thrown = assertFailsWith<GiftFundingException> { fixture.useCase.submit(fixture.quote) }

            assertEquals(GiftFundingError.SUBMIT_UNCERTAIN, thrown.error)
            coVerifyOrder {
                fixture.storage.setFundingAttemptedAt(ID, any())
                fixture.proposalDataSource.createTransactions(any(), any())
                fixture.storage.recordFundingCreated(ID, TXID, any())
            }
            coVerify(exactly = 0) {
                fixture.proposalDataSource.submitTransaction(transaction = any(), endpoint = any())
            }
        }

    @Test
    fun `keeps a rejected transaction unresolved because the SDK may resubmit it`() =
        runTest {
            val fixture =
                Fixture(submitResult = SubmitResult.Failure(txIds = emptyList(), code = 1, description = null))

            val thrown = assertFailsWith<GiftFundingException> { fixture.useCase.submit(fixture.quote) }

            assertEquals(GiftFundingError.SUBMIT_UNCERTAIN, thrown.error)
            coVerify(exactly = 1) { fixture.storage.recordFundingCreated(ID, TXID, any()) }
            coVerify(exactly = 1) { fixture.storage.setFundingAttemptedAt(ID, any()) }
        }

    @Test
    fun `reports a partial broadcast as uncertain`() =
        runTest {
            val fixture =
                Fixture(submitResult = SubmitResult.Partial(txIds = listOf(TXID), statuses = listOf("failed")))

            val thrown = assertFailsWith<GiftFundingException> { fixture.useCase.submit(fixture.quote) }

            assertEquals(GiftFundingError.SUBMIT_UNCERTAIN, thrown.error)
        }

    @Test
    fun `reports a submit that threw as uncertain`() =
        runTest {
            val fixture = Fixture(submitResult = SubmitResult.Success(listOf(TXID)))
            coEvery {
                fixture.proposalDataSource.submitTransaction(transaction = any(), endpoint = any())
            } throws IllegalStateException("the socket died mid-submit")

            val thrown = assertFailsWith<GiftFundingException> { fixture.useCase.submit(fixture.quote) }

            assertEquals(GiftFundingError.SUBMIT_UNCERTAIN, thrown.error)
        }

    @Test
    fun `refuses to re-prepare a card whose broadcast was already attempted`() =
        runTest {
            val fixture = Fixture(submitResult = SubmitResult.Success(listOf(TXID)))
            val attempted = CARD.copy(fundingAttemptedAt = "2026-08-20T12:00:01Z")
            coEvery { fixture.storage.get(ID) } returns attempted

            val thrown =
                assertFailsWith<GiftFundingException> {
                    fixture.useCase.prepare(amount = Zatoshi(AMOUNT), existing = attempted)
                }

            // Stepping back to the details and continuing again clears the screen's error and
            // lands here with the same card; only the record can refuse it.
            assertEquals(GiftFundingError.SUBMIT_UNCERTAIN, thrown.error)
            coVerify(exactly = 0) { fixture.accountDataSource.getSelectedAccount() }
        }

    @Test
    fun `refuses to re-prepare a card that already has a funding txid`() =
        runTest {
            val fixture = Fixture(submitResult = SubmitResult.Success(listOf(TXID)))
            val funded = CARD.copy(fundingTxid = TXID)
            coEvery { fixture.storage.get(ID) } returns funded

            val thrown =
                assertFailsWith<GiftFundingException> {
                    fixture.useCase.prepare(amount = Zatoshi(AMOUNT), existing = funded)
                }

            assertEquals(GiftFundingError.SUBMIT_UNCERTAIN, thrown.error)
            coVerify(exactly = 0) { fixture.accountDataSource.getSelectedAccount() }
        }

    @Test
    fun `refuses a card the caller still thinks is unfunded`() =
        runTest {
            val fixture = Fixture(submitResult = SubmitResult.Success(listOf(TXID)))
            // The screen holds its copy across a trip the sender can leave and come back from, so
            // the attempt can land while that copy still says draft. Trusting it funds twice.
            coEvery { fixture.storage.get(ID) } returns CARD.copy(fundingTxid = TXID)

            val thrown =
                assertFailsWith<GiftFundingException> {
                    fixture.useCase.prepare(amount = Zatoshi(AMOUNT), existing = CARD)
                }

            assertEquals(GiftFundingError.SUBMIT_UNCERTAIN, thrown.error)
        }

    @Test
    fun `mints again for a draft that was superseded`() =
        runTest {
            val fixture = Fixture(submitResult = SubmitResult.Success(listOf(TXID)))
            // Gone from the store: a later mint superseded it. Pricing it would build a proposal
            // against an address whose only record no longer exists.
            coEvery { fixture.storage.get(ID) } returns null
            coEvery { fixture.createGiftCard(any(), any(), any(), any()) } returns CARD

            fixture.useCase.prepare(amount = Zatoshi(AMOUNT), existing = CARD)

            coVerify(exactly = 1) { fixture.createGiftCard(any(), any(), any(), any()) }
        }

    @Test
    fun `re-prices a draft that is still on file without minting another`() =
        runTest {
            val fixture = Fixture(submitResult = SubmitResult.Success(listOf(TXID)))
            coEvery { fixture.storage.get(ID) } returns CARD

            val quote = fixture.useCase.prepare(amount = Zatoshi(AMOUNT), existing = CARD)

            assertEquals(CARD, quote.card)
            coVerify(exactly = 0) { fixture.createGiftCard(any(), any(), any(), any()) }
        }

    @Test
    fun `new card and proposal use the same resolved source account`() =
        runTest {
            val fixture = Fixture(submitResult = SubmitResult.Success(listOf(TXID)))
            val laterSelection = mockk<WalletAccount>(relaxed = true)
            coEvery { fixture.accountDataSource.getSelectedAccount() } returnsMany
                listOf(fixture.account, laterSelection)
            coEvery { fixture.createGiftCard(any(), any(), any(), fixture.account) } returns CARD

            fixture.useCase.prepare(amount = Zatoshi(AMOUNT))

            coVerify(exactly = 1) { fixture.accountDataSource.getSelectedAccount() }
            coVerify(exactly = 1) { fixture.createGiftCard(any(), any(), any(), fixture.account) }
            coVerify(exactly = 1) {
                fixture.proposalDataSource.createProposal(account = fixture.account, send = any())
            }
        }

    @Test
    fun `maximum monetary amount fails cleanly before reserve addition`() =
        runTest {
            val fixture = Fixture(submitResult = SubmitResult.Success(listOf(TXID)))

            val thrown =
                assertFailsWith<GiftFundingException> {
                    fixture.useCase.prepare(amount = Zatoshi(Zatoshi.MAX_INCLUSIVE))
                }

            assertEquals(GiftFundingError.INSUFFICIENT_FUNDS, thrown.error)
            coVerify(exactly = 0) { fixture.createGiftCard(any(), any(), any(), any()) }
            coVerify(exactly = 0) { fixture.proposalDataSource.createProposal(any(), any()) }
        }

    @Test
    fun `retry uses the card source account when another account is selected`() =
        runTest {
            val fixture = Fixture(submitResult = SubmitResult.Success(listOf(TXID)))
            val retryable = CARD.copy(fundingFailures = listOf(noTransactionFailure()))
            val selected = mockk<WalletAccount>(relaxed = true)
            coEvery { fixture.storage.get(ID) } returns retryable
            coEvery { fixture.accountDataSource.getSelectedAccount() } returns selected

            fixture.useCase.prepare(amount = Zatoshi(AMOUNT), existing = retryable)

            coVerify(exactly = 0) { fixture.accountDataSource.getSelectedAccount() }
            coVerify(exactly = 1) {
                fixture.proposalDataSource.createProposal(account = fixture.account, send = any())
            }
        }

    @Test
    fun `retry fails before proposal creation when its source account is unavailable`() =
        runTest {
            val fixture = Fixture(submitResult = SubmitResult.Success(listOf(TXID)))
            val retryable = CARD.copy(fundingFailures = listOf(noTransactionFailure()))
            coEvery { fixture.storage.get(ID) } returns retryable
            coEvery { fixture.accountDataSource.getAllAccounts() } returns emptyList()

            val thrown =
                assertFailsWith<GiftFundingException> {
                    fixture.useCase.prepare(amount = Zatoshi(AMOUNT), existing = retryable)
                }

            assertEquals(GiftFundingError.PROPOSAL_FAILED, thrown.error)
            coVerify(exactly = 0) { fixture.proposalDataSource.createProposal(any(), any()) }
        }

    @Test
    fun `submit refuses a retry quote when terminal history changed after review`() =
        runTest {
            val fixture = Fixture(submitResult = SubmitResult.Success(listOf(TXID)))
            val quotedCard = CARD.copy(fundingFailures = listOf(noTransactionFailure()))
            val quote = fixture.quote.copy(card = quotedCard)
            coEvery { fixture.storage.get(ID) } returns
                quotedCard.copy(
                    fundingFailures =
                        quotedCard.fundingFailures +
                            GiftFundingFailure(
                                reason = GiftFundingFailureReason.EXPIRED,
                                attemptedAt = LATER,
                                transactionId = OTHER_TXID,
                                detectedAt = LATEST,
                            )
                )

            val thrown = assertFailsWith<GiftFundingException> { fixture.useCase.submit(quote) }

            assertEquals(GiftFundingError.SUBMIT_UNCERTAIN, thrown.error)
            coVerify(exactly = 0) { fixture.storage.setFundingAttemptedAt(any(), any()) }
            coVerify(exactly = 0) { fixture.proposalDataSource.createTransactions(any(), any()) }
        }

    @Test
    fun `concurrent submit and reconciliation create one active transaction`() =
        runTest {
            val operationLock = GiftFundingOperationLock()
            val fixture = Fixture(SubmitResult.Success(listOf(TXID)), operationLock)
            var current = CARD.copy(fundingFailures = listOf(noTransactionFailure()))
            val markerWritten = CompletableDeferred<Unit>()
            val reconciliationRead = CompletableDeferred<Unit>()
            coEvery { fixture.storage.get(ID) } answers { current }
            coEvery { fixture.storage.getAll() } answers {
                reconciliationRead.complete(Unit)
                listOf(current)
            }
            coEvery { fixture.storage.setFundingAttemptedAt(ID, any()) } coAnswers {
                current = current.copy(fundingAttemptedAt = LATEST)
                markerWritten.complete(Unit)
                reconciliationRead.await()
            }
            coEvery { fixture.storage.recordFundingCreated(ID, TXID, any()) } answers {
                current = current.copy(fundingTxid = TXID, fundingCreatedAt = LATEST)
            }
            coEvery { fixture.storage.recordFundingSubmitted(ID, TXID, any()) } answers {
                current =
                    current.copy(
                        fundingTxid = TXID,
                        fundingAttemptedAt = null,
                        fundingSubmittedAt = LATEST,
                    )
            }
            val pending =
                mockk<TransactionOverview>().also {
                    every { it.txId } returns TransactionId.new(TXID)
                    every { it.isSentTransaction } returns true
                    every { it.transactionState } returns TransactionState.Pending
                }
            val repository = mockk<TransactionRepository>()
            coEvery { repository.getSyncedAccountTransactionSnapshot(ACCOUNT_ID) } returns
                SyncedAccountTransactionSnapshot(
                    transactions = listOf(pending),
                    recipientsByTransactionId = mapOf(TXID to setOf(CARD.address)),
                )
            val confirm = ConfirmGiftCardFundingUseCase(fixture.storage, repository, operationLock)
            val quote = fixture.quote.copy(card = current)

            val submit = async { fixture.useCase.submit(quote) }
            markerWritten.await()
            val reconcile = async { confirm.reconcile() }
            assertEquals(TXID, submit.await())
            reconcile.await()

            coVerify(exactly = 1) { fixture.proposalDataSource.createTransactions(any(), any()) }
            coVerify(exactly = 1) { fixture.storage.setFundingAttemptedAt(ID, any()) }
            coVerify(exactly = 0) { fixture.storage.markFundingNotCreated(any(), any()) }
            assertEquals(TXID, current.fundingTxid)
        }

    private class Fixture(
        submitResult: SubmitResult,
        operationLock: GiftFundingOperationLock = GiftFundingOperationLock(),
    ) {
        val storage =
            mockk<GiftCardStorageProvider>(relaxed = true).also {
                coEvery { it.get(ID) } returns CARD
            }
        val createGiftCard = mockk<CreateGiftCardUseCase>(relaxed = true)

        /** Solvent, so the cheap pre-mint refusal never fires and `prepare` runs to a quote. */
        val account =
            mockk<WalletAccount>(relaxed = true).also {
                val sdkAccount = mockk<Account>()
                every { sdkAccount.accountUuid } returns ACCOUNT_UUID
                every { it.sdkAccount } returns sdkAccount
                every { it.canSpend(any()) } returns true
            }

        val accountDataSource =
            mockk<AccountDataSource>(relaxed = true).also {
                coEvery { it.getSelectedAccount() } returns account
                coEvery { it.getAllAccounts() } returns listOf(account)
            }

        private val proposal = mockk<RegularTransactionProposal>(relaxed = true)

        val proposalDataSource =
            mockk<ProposalDataSource>(relaxed = true).also {
                val transaction = mockk<CreatedTransaction>()
                every { transaction.txIdString() } returns TXID
                coEvery { it.createProposal(any(), any()) } returns proposal
                coEvery { it.createTransactions(any(), any()) } returns listOf(transaction)
                coEvery { it.submitTransaction(transaction = transaction, endpoint = any()) } returns submitResult
            }

        val quote =
            GiftFundingQuote(
                card = CARD,
                proposal = mockk<RegularTransactionProposal>(relaxed = true),
                claimFeeReserve = Zatoshi(10_000L),
                networkFee = Zatoshi(10_000L),
            )

        val useCase =
            FundGiftCardUseCase(
                createGiftCard = createGiftCard,
                accountDataSource = accountDataSource,
                proposalDataSource = proposalDataSource,
                zashiSpendingKeyDataSource = mockk<ZashiSpendingKeyDataSource>(relaxed = true),
                giftCardStorageProvider = storage,
                persistableWalletProvider = mockk<PersistableWalletProvider>(relaxed = true),
                giftFundingOperationLock = operationLock,
            )
    }

    private companion object {
        const val ID = "6f1c0f6e-0b6b-4f2e-9a5a-6f1c0f6e0b6b"
        const val TXID = "f00d"
        const val OTHER_TXID = "beef"
        const val AMOUNT = 100_000_000L
        const val LATER = "2026-08-20T12:05:00Z"
        const val LATEST = "2026-08-20T12:10:00Z"
        val ACCOUNT_UUID = AccountUuid.new(ByteArray(16))
        val ACCOUNT_ID = ACCOUNT_UUID.toStorageKeyId()

        /** BIP-39 test vector for all-zero entropy. Never a real wallet. */
        const val MNEMONIC =
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon art"

        val CARD =
            StoredGiftCard(
                id = ID,
                network = "main",
                address = "u1exampleunifiedaddressforgiftcardtests",
                mnemonic = MNEMONIC,
                amountZatoshi = AMOUNT,
                birthdayHeight = 2_800_000L,
                sourceAccountUuid = ACCOUNT_ID,
                createdAt = "2026-08-20T12:00:00Z",
                updatedAt = "2026-08-20T12:00:00Z",
                status = GiftCardStatus.DRAFT,
            )

        fun noTransactionFailure() =
            GiftFundingFailure(
                reason = GiftFundingFailureReason.NO_TRANSACTION,
                attemptedAt = LATER,
                detectedAt = LATEST,
            )
    }
}
