// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.datasource.RegularTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.ZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.provider.GiftCardStorageProvider
import co.electriccoin.zcash.ui.screen.gift.model.GiftCardStatus
import co.electriccoin.zcash.ui.screen.gift.model.StoredGiftCard
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
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

            coVerify { fixture.storage.recordFundingSubmitted(id = ID, fundingTxid = TXID, at = any()) }
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
    fun `reports a storage failure before the broadcast as nothing sent`() =
        runTest {
            val fixture = Fixture(submitResult = SubmitResult.Success(listOf(TXID)))
            coEvery {
                fixture.storage.setFundingAttemptedAt(any(), any())
            } throws IllegalStateException("store is full")

            val thrown = assertFailsWith<GiftFundingException> { fixture.useCase.submit(fixture.quote) }

            assertEquals(GiftFundingError.PROPOSAL_FAILED, thrown.error)
            coVerify(exactly = 0) {
                fixture.proposalDataSource.submitTransaction(any<Proposal>(), any<UnifiedSpendingKey>())
            }
        }

    @Test
    fun `still reports a rejection when the attempt flag cannot be cleared`() =
        runTest {
            val fixture =
                Fixture(submitResult = SubmitResult.Failure(txIds = emptyList(), code = 1, description = null))
            coEvery { fixture.storage.setFundingAttemptedAt(ID, null) } throws IllegalStateException("store is full")

            val thrown = assertFailsWith<GiftFundingException> { fixture.useCase.submit(fixture.quote) }

            // The network never took it, and that is knowable regardless of what the store did.
            assertEquals(GiftFundingError.SUBMIT_REJECTED, thrown.error)
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
                fixture.proposalDataSource.submitTransaction(any<Proposal>(), any<UnifiedSpendingKey>())
            } throws IllegalStateException("the socket died mid-submit")

            val thrown = assertFailsWith<GiftFundingException> { fixture.useCase.submit(fixture.quote) }

            assertEquals(GiftFundingError.SUBMIT_UNCERTAIN, thrown.error)
        }

    @Test
    fun `refuses to re-prepare a card whose broadcast was already attempted`() =
        runTest {
            val fixture = Fixture(submitResult = SubmitResult.Success(listOf(TXID)))

            val thrown =
                assertFailsWith<GiftFundingException> {
                    fixture.useCase.prepare(
                        amount = Zatoshi(AMOUNT),
                        existing = CARD.copy(fundingAttemptedAt = "2026-08-20T12:00:01Z"),
                    )
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

            val thrown =
                assertFailsWith<GiftFundingException> {
                    fixture.useCase.prepare(amount = Zatoshi(AMOUNT), existing = CARD.copy(fundingTxid = TXID))
                }

            assertEquals(GiftFundingError.SUBMIT_UNCERTAIN, thrown.error)
            coVerify(exactly = 0) { fixture.accountDataSource.getSelectedAccount() }
        }

    private class Fixture(
        submitResult: SubmitResult,
    ) {
        val storage = mockk<GiftCardStorageProvider>(relaxed = true)
        val accountDataSource = mockk<AccountDataSource>(relaxed = true)

        val proposalDataSource =
            mockk<ProposalDataSource>(relaxed = true).also {
                coEvery { it.submitTransaction(any<Proposal>(), any<UnifiedSpendingKey>()) } returns submitResult
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
                createGiftCard = mockk<CreateGiftCardUseCase>(relaxed = true),
                accountDataSource = accountDataSource,
                proposalDataSource = proposalDataSource,
                zashiSpendingKeyDataSource = mockk<ZashiSpendingKeyDataSource>(relaxed = true),
                giftCardStorageProvider = storage,
            )
    }

    private companion object {
        const val ID = "6f1c0f6e-0b6b-4f2e-9a5a-6f1c0f6e0b6b"
        const val TXID = "f00d"
        const val AMOUNT = 100_000_000L

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
                sourceAccountUuid = "account-uuid",
                createdAt = "2026-08-20T12:00:00Z",
                updatedAt = "2026-08-20T12:00:00Z",
                status = GiftCardStatus.DRAFT,
            )
    }
}
