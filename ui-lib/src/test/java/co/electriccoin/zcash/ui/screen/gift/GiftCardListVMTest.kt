// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.gift

import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.provider.GiftCardStorageProvider
import co.electriccoin.zcash.ui.common.repository.ExchangeRateRepository
import co.electriccoin.zcash.ui.common.repository.SwapAssetsData
import co.electriccoin.zcash.ui.common.repository.SwapRepository
import co.electriccoin.zcash.ui.common.usecase.CheckGiftCardClaimedUseCase
import co.electriccoin.zcash.ui.common.usecase.ConfirmGiftCardFundingUseCase
import co.electriccoin.zcash.ui.common.usecase.CopyToClipboardUseCase
import co.electriccoin.zcash.ui.common.usecase.GiftCardCheckResult
import co.electriccoin.zcash.ui.common.usecase.ShareGiftLinkUseCase
import co.electriccoin.zcash.ui.common.wallet.ExchangeRateState
import co.electriccoin.zcash.ui.screen.gift.model.GiftCardStatus
import co.electriccoin.zcash.ui.screen.gift.model.GiftLinkCodec
import co.electriccoin.zcash.ui.screen.gift.model.StoredGiftCard
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The screen that exists so pressing Done — or the process dying on the ready screen — is not the
 * end of a funded card. Every stored card has to stay re-shareable from here, because the ephemeral
 * seed is random rather than derived from the wallet seed and there is no reclaim.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GiftCardListVMTest {
    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `surfaces a card whose funding was submitted but never shared`() =
        runTest {
            val fixture = fixture(card(fundingTxid = TXID))

            val state = collectState(fixture)

            val item = state.items.single()
            assertEquals(GiftCardListStatus.SUBMITTED, item.status)
        }

    @Test
    fun `rebuilds a claimable link from storage and records the hand-off`() =
        runTest {
            val fixture = fixture(card(fundingTxid = TXID))
            val state = collectState(fixture)

            assertNotNull(state.items.single().handOff).onShare("share")
            advanceUntilIdle()

            assertEquals(MNEMONIC, GiftLinkCodec.decode(fixture.sharedLink.captured, ZcashNetwork.Mainnet).mnemonic)
            verify(exactly = 1) { fixture.shareGiftLink(cardId = ID, link = any(), sharePickerText = any()) }
            // Opening the sheet is not the hand-off. The chooser reports the target the sender
            // picked, and only that marks the card — a cancelled sheet must leave it protected.
            coVerify(exactly = 0) { fixture.shareGiftLink.markHandedOut(any()) }
        }

    @Test
    fun `copying the link records the hand-off itself`() =
        runTest {
            val fixture = fixture(card(fundingTxid = TXID))
            val state = collectState(fixture)

            assertNotNull(state.items.single().handOff).onCopy()
            advanceUntilIdle()

            // The route that cannot fail to report. A chooser that never tells us which target was
            // picked would otherwise leave the card blocking a wallet reset with no way out.
            coVerify(exactly = 1) { fixture.shareGiftLink.markHandedOut(ID) }
        }

    @Test
    fun `says so when a copied link could not be recorded as handed out`() =
        runTest {
            val fixture = fixture(card(fundingTxid = TXID))
            coEvery { fixture.shareGiftLink.markHandedOut(any()) } returns false
            val state = collectState(fixture)

            assertNotNull(state.items.single().handOff).onCopy()
            advanceUntilIdle()

            // The link is out and the card still counts as unshared. Only the sender can act on
            // that, and only if they are told.
            assertEquals(GiftCardListError.HANDOFF_FAILED, collectState(fixture).error)
        }

    @Test
    fun `hides a draft that was never funded`() =
        runTest {
            val fixture = fixture(card())

            // Minting happens before funding, so backing out of the review screen strands a draft
            // nothing was ever sent to. It cannot be handed out, checked, or recovered from.
            assertTrue(collectState(fixture).items.isEmpty())
        }

    @Test
    fun `still hands out a card whose broadcast outcome was never seen`() =
        runTest {
            val fixture = fixture(card(fundingAttemptedAt = ATTEMPTED_AT))

            val item = collectState(fixture).items.single()

            // The money may already have gone, and then this link is the only route to it.
            assertEquals(GiftCardListStatus.UNRESOLVED, item.status)
            assertNotNull(item.handOff)
        }

    @Test
    fun `refuses to hand out a card that was already collected`() =
        runTest {
            val fixture = fixture(card(fundingTxid = TXID, status = GiftCardStatus.CLAIMED))

            val item = collectState(fixture).items.single()

            // Its link is spent; passing it on hands the recipient a dead card. Both hand-off
            // controls go, and the row hides them rather than grey them: a settled card is a
            // receipt, and a disabled button on it offers something there is no version of.
            assertEquals(GiftCardListStatus.CLAIMED, item.status)
            assertNull(item.handOff)
            // Nothing left to check either.
            assertEquals(GiftCheckControl.Hidden, item.check)
        }

    @Test
    fun `reports a funding that has not reached the card as a finding, not a collection`() =
        runTest {
            val fixture = fixture(card(fundingTxid = TXID))
            coEvery { fixture.checkGiftCardClaimed(any(), any()) } returns GiftCardCheckResult.FUNDING_PENDING
            val state = collectState(fixture)

            assertIs<GiftCheckControl.Ready>(state.items.single().check).onCheck()
            advanceUntilIdle()

            val settled = collectState(fixture)
            // An empty wallet whose funding never arrived is not a collected card. Saying otherwise
            // settles it terminally — no re-share, no re-check — while the money may still land.
            assertEquals(GiftCardListNotice.CHECK_FUNDING_PENDING, settled.notice)
            assertNull(settled.error)
        }

    @Test
    fun `disables the collected check until there is a transaction to look for`() =
        runTest {
            val unresolved = fixture(card(fundingAttemptedAt = ATTEMPTED_AT))

            val pending = collectState(unresolved).items.single()

            // Money may well have left for this card, so the reason must not claim otherwise.
            assertEquals(GiftCardListStatus.UNRESOLVED, pending.status)
            assertEquals(GiftCheckControl.Blocked(GiftCheckBlocked.NO_TRANSACTION), pending.check)

            val funded = fixture(card(fundingTxid = TXID))
            assertIs<GiftCheckControl.Ready>(collectState(funded).items.single().check)
        }

    @Test
    fun `shows when a card was last confirmed unclaimed`() =
        runTest {
            val fixture = fixture(card(fundingTxid = TXID, lastCheckedAt = ATTEMPTED_AT))

            // Without this the scan finishes and the row looks exactly as it did before it ran.
            assertNotNull(collectState(fixture).items.single().lastCheckedAt)
        }

    @Test
    fun `drops the last-checked note once a card is collected`() =
        runTest {
            val fixture =
                fixture(card(fundingTxid = TXID, lastCheckedAt = ATTEMPTED_AT, status = GiftCardStatus.CLAIMED))

            // "Collected" already says everything; a stale "still on the card" beside it would lie.
            assertNull(collectState(fixture).items.single().lastCheckedAt)
        }

    /**
     * The priced case cannot be asserted here: building the figure reads `FiatCurrency.symbol`,
     * which calls `android.icu.util.Currency` and returns null off-device, and this module has no
     * Robolectric. What is worth pinning down is the half that does not need one — a card must
     * never invent a figure when the wallet has no rate behind it.
     */
    @Test
    fun `leaves a card unpriced rather than guessing when there is no rate`() =
        runTest {
            val fixture = fixture(card(fundingTxid = TXID))

            assertNull(collectState(fixture).items.single().fiat)
        }

    @Test
    fun `prints a card on stock chosen by its denomination`() =
        runTest {
            val big = fixture(card(amountZatoshi = 1_000_000_000L, fundingTxid = TXID))
            val small = fixture(card(amountZatoshi = 4_000_000L, fundingTxid = TXID))

            assertEquals(GiftCardTier.AMBER, collectState(big).items.single().tier)
            assertEquals(GiftCardTier.BONE, collectState(small).items.single().tier)
        }

    @Test
    fun `prints a collected card on spent stock whatever it was worth`() =
        runTest {
            val fixture =
                fixture(
                    card(amountZatoshi = 1_000_000_000L, fundingTxid = TXID, status = GiftCardStatus.CLAIMED)
                )

            assertEquals(GiftCardTier.SPENT, collectState(fixture).items.single().tier)
        }

    @Test
    fun `renders rather than throwing when the store will not decode`() =
        runTest {
            val fixture = fixture(card(), storeThrows = true)

            val state = collectState(fixture)

            assertTrue(state.isCorrupted)
            assertTrue(state.items.isEmpty())
        }

    /** The main dispatcher has to be in place before `viewModelScope` is touched. */
    private fun TestScope.fixture(
        card: StoredGiftCard,
        storeThrows: Boolean = false,
    ): Fixture {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        return Fixture(card, storeThrows)
    }

    private fun TestScope.collectState(fixture: Fixture): GiftCardListState {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { fixture.vm.state.collect {} }
        advanceUntilIdle()
        return requireNotNull(fixture.vm.state.value)
    }

    private class Fixture(
        card: StoredGiftCard,
        storeThrows: Boolean,
    ) {
        val sharedLink = slot<String>()

        val shareGiftLink =
            mockk<ShareGiftLinkUseCase>(relaxed = true).also {
                every { it(cardId = any(), link = capture(sharedLink), sharePickerText = any()) } returns true
                coEvery { it.markHandedOut(any()) } returns true
            }
        val copyToClipboard = mockk<CopyToClipboardUseCase>(relaxed = true)
        val checkGiftCardClaimed = mockk<CheckGiftCardClaimedUseCase>(relaxed = true)

        /** Opted out and no catalog price, so nothing here depends on a formatted fiat figure. */
        private val exchangeRate =
            mockk<ExchangeRateRepository>().also {
                every { it.state } returns MutableStateFlow(ExchangeRateState.OptedOut)
            }

        private val swaps =
            mockk<SwapRepository>().also {
                every { it.assets } returns MutableStateFlow(SwapAssetsData())
            }

        private val storage =
            mockk<GiftCardStorageProvider>().also { storage ->
                every { storage.observe() } returns
                    if (storeThrows) {
                        flow { throw IllegalStateException("undecodable") }
                    } else {
                        MutableStateFlow(listOf(card))
                    }
                coEvery { storage.get(card.id) } returns card
            }

        val vm =
            GiftCardListVM(
                giftCardStorageProvider = storage,
                confirmGiftCardFunding = mockk<ConfirmGiftCardFundingUseCase>(relaxed = true),
                checkGiftCardClaimed = checkGiftCardClaimed,
                exchangeRateRepository = exchangeRate,
                swapRepository = swaps,
                shareGiftLink = shareGiftLink,
                copyToClipboard = copyToClipboard,
                navigationRouter = mockk<NavigationRouter>(relaxed = true),
            )
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

        fun card(
            amountZatoshi: Long = 100_000_000L,
            fundingTxid: String? = null,
            fundingAttemptedAt: String? = null,
            lastCheckedAt: String? = null,
            status: GiftCardStatus = GiftCardStatus.DRAFT,
        ) = StoredGiftCard(
            id = ID,
            network = "main",
            address = "u1exampleunifiedaddressforgiftcardtests",
            mnemonic = MNEMONIC,
            amountZatoshi = amountZatoshi,
            birthdayHeight = 2_800_000L,
            sourceAccountUuid = "account-uuid",
            createdAt = "2026-08-20T12:00:00Z",
            updatedAt = "2026-08-20T12:00:00Z",
            status = status,
            fundingTxid = fundingTxid,
            fundingAttemptedAt = fundingAttemptedAt,
            lastCheckedAt = lastCheckedAt,
        )
    }
}
