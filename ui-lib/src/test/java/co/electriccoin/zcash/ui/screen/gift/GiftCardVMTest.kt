// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.gift

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.RegularTransactionProposal
import co.electriccoin.zcash.ui.common.provider.GiftCardStorageProvider
import co.electriccoin.zcash.ui.common.repository.ExchangeRateRepository
import co.electriccoin.zcash.ui.common.repository.SwapAssetsData
import co.electriccoin.zcash.ui.common.repository.SwapRepository
import co.electriccoin.zcash.ui.common.security.PinVerifyState
import co.electriccoin.zcash.ui.common.security.SecretAuthGate
import co.electriccoin.zcash.ui.common.usecase.ConfirmGiftCardFundingUseCase
import co.electriccoin.zcash.ui.common.usecase.FundGiftCardUseCase
import co.electriccoin.zcash.ui.common.usecase.GiftFundingQuote
import co.electriccoin.zcash.ui.common.usecase.ShareGiftLinkUseCase
import co.electriccoin.zcash.ui.common.wallet.ExchangeRateState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
import co.electriccoin.zcash.ui.screen.gift.model.GiftCardStatus
import co.electriccoin.zcash.ui.screen.gift.model.GiftFundingFailure
import co.electriccoin.zcash.ui.screen.gift.model.GiftFundingFailureReason
import co.electriccoin.zcash.ui.screen.gift.model.StoredGiftCard
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.math.BigDecimal
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@OptIn(ExperimentalCoroutinesApi::class)
class GiftCardVMTest {
    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `persisted retryable card replaces ready success with same-card recovery`() =
        runTest {
            val fixture = fixture()
            reachReady(fixture)

            fixture.setStoredCard(retryableCard(), publish = true)
            advanceUntilIdle()

            val state = fixture.vm.state.value
            assertEquals(GiftCardStage.UNAVAILABLE, state.stage)
            assertNotNull(state.onOpenSavedCards)
        }

    @Test
    fun `share re-reads storage and refuses every non-handable ready card`() =
        runTest {
            nonHandableCards().forEach { card ->
                val fixture = fixture()
                reachReady(fixture)

                fixture.setStoredCard(card, publish = false)
                fixture.vm.state.value.onShare("share")
                advanceUntilIdle()

                verify(exactly = 0) { fixture.shareGiftLink(any(), any(), any()) }
                assertEquals(GiftCardStage.UNAVAILABLE, fixture.vm.state.value.stage)
            }
        }

    private fun TestScope.fixture(): Fixture {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        return Fixture()
    }

    private suspend fun TestScope.reachReady(fixture: Fixture) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { fixture.vm.state.collect {} }
        advanceUntilIdle()
        fixture.vm.state.value.onAmountChange(NumberTextFieldInnerState.fromAmount(BigDecimal.ONE))
        fixture.vm.state.value.onContinue()
        advanceUntilIdle()
        assertEquals(GiftCardStage.REVIEW, fixture.vm.state.value.stage)
        fixture.vm.state.value.onConfirm()
        advanceUntilIdle()
        assertEquals(GiftCardStage.READY, fixture.vm.state.value.stage)
    }

    private class Fixture {
        private val draft = card()
        private val storedCards = MutableStateFlow(listOf(draft))
        private var storedCard = draft

        val shareGiftLink =
            mockk<ShareGiftLinkUseCase>(relaxed = true).also {
                every { it(any(), any(), any()) } returns true
                coEvery { it.markHandedOut(any()) } returns true
            }

        private val storage =
            mockk<GiftCardStorageProvider>().also {
                every { it.observe() } returns storedCards
                coEvery { it.get(ID) } answers { storedCard }
            }
        private val fundingQuote =
            GiftFundingQuote(
                card = draft,
                proposal = mockk<RegularTransactionProposal>(relaxed = true),
                claimFeeReserve = Zatoshi(10_000L),
                networkFee = Zatoshi(10_000L),
            )
        private val fundGiftCard =
            mockk<FundGiftCardUseCase>().also {
                coEvery { it.prepare(any(), any(), any(), any()) } returns fundingQuote
                coEvery { it.submit(fundingQuote) } coAnswers {
                    setStoredCard(draft.copy(fundingTxid = TXID), publish = true)
                    TXID
                }
            }
        private val secretAuthGate =
            mockk<SecretAuthGate>().also {
                every { it.pinPrompt } returns MutableStateFlow<PinVerifyState?>(null)
                coEvery { it.authenticate(any(), any()) } returns true
            }

        val vm =
            GiftCardVM(
                fundGiftCard = fundGiftCard,
                confirmGiftCardFunding = mockk<ConfirmGiftCardFundingUseCase>(relaxed = true),
                shareGiftLink = shareGiftLink,
                secretAuthGate = secretAuthGate,
                accountDataSource =
                    mockk<AccountDataSource>().also {
                        every { it.selectedAccount } returns flowOf(null)
                    },
                exchangeRateRepository =
                    mockk<ExchangeRateRepository>().also {
                        every { it.state } returns MutableStateFlow(ExchangeRateState.OptedOut)
                    },
                swapRepository =
                    mockk<SwapRepository>().also {
                        every { it.assets } returns MutableStateFlow(SwapAssetsData())
                    },
                giftCardStorageProvider = storage,
                navigationRouter = mockk<NavigationRouter>(relaxed = true),
            )

        fun setStoredCard(card: StoredGiftCard, publish: Boolean) {
            storedCard = card
            if (publish) storedCards.value = listOf(card)
        }
    }

    private companion object {
        const val ID = "6f1c0f6e-0b6b-4f2e-9a5a-6f1c0f6e0b6b"
        const val TXID = "f00d"
        const val ATTEMPTED_AT = "2026-08-20T12:00:01Z"

        /** BIP-39 test vector for all-zero entropy. Never a real wallet. */
        const val MNEMONIC =
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon art"

        fun card() =
            StoredGiftCard(
                id = ID,
                network = "main",
                address = "u1exampleunifiedaddressforgiftcardtests",
                mnemonic = MNEMONIC,
                amountZatoshi = 100_000_000L,
                birthdayHeight = 2_800_000L,
                sourceAccountUuid = "account-uuid",
                createdAt = "2026-08-20T12:00:00Z",
                updatedAt = "2026-08-20T12:00:00Z",
                status = GiftCardStatus.DRAFT,
            )

        fun retryableCard() =
            card().copy(
                fundingFailures =
                    listOf(
                        GiftFundingFailure(
                            reason = GiftFundingFailureReason.EXPIRED,
                            attemptedAt = ATTEMPTED_AT,
                            transactionId = TXID,
                            detectedAt = "2026-08-20T12:10:00Z",
                        )
                    )
            )

        fun nonHandableCards() =
            listOf(
                card(),
                retryableCard(),
                card().copy(fundingTxid = TXID, status = GiftCardStatus.CLAIMED),
            )
    }
}
