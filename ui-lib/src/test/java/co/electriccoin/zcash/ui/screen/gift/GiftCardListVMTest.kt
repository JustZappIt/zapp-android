// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.gift

import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.provider.GiftCardStorageProvider
import co.electriccoin.zcash.ui.common.provider.ReceivedGiftStorageProvider
import co.electriccoin.zcash.ui.common.usecase.CheckGiftCardClaimedUseCase
import co.electriccoin.zcash.ui.common.usecase.ConfirmGiftCardFundingUseCase
import co.electriccoin.zcash.ui.common.usecase.CopyToClipboardUseCase
import co.electriccoin.zcash.ui.common.usecase.ShareGiftLinkUseCase
import co.electriccoin.zcash.ui.screen.gift.model.GiftCardStatus
import co.electriccoin.zcash.ui.screen.gift.model.GiftLinkCodec
import co.electriccoin.zcash.ui.screen.gift.model.ReceivedGift
import co.electriccoin.zcash.ui.screen.gift.model.StoredGiftCard
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
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
            // Archiving would hide the very record that blocks the wallet wipe.
            assertNull(item.onArchive)
        }

    @Test
    fun `rebuilds a claimable link from storage and records the hand-off`() =
        runTest {
            val fixture = fixture(card(fundingTxid = TXID))
            val state = collectState(fixture)

            assertNotNull(state.items.single().onCopy).invoke()
            advanceUntilIdle()

            assertEquals(MNEMONIC, GiftLinkCodec.decode(fixture.copiedLink.captured, ZcashNetwork.Mainnet).mnemonic)
            coVerify(exactly = 1) { fixture.shareGiftLink.markHandedOut(ID) }
        }

    @Test
    fun `offers archive only once the link has left the device`() =
        runTest {
            val fixture = fixture(card(fundingTxid = TXID, status = GiftCardStatus.SHARED))

            val state = collectState(fixture)

            assertEquals(GiftCardListStatus.SHARED, state.items.single().status)
            assertNotNull(state.items.single().onArchive)
        }

    @Test
    fun `refuses to hand out a draft that was never funded`() =
        runTest {
            val fixture = fixture(card())

            val item = collectState(fixture).items.single()

            // The link would encode and look real, and pay the recipient nothing.
            assertEquals(GiftCardListStatus.UNFUNDED, item.status)
            assertNull(item.onCopy)
            assertNull(item.onShare)
        }

    @Test
    fun `still hands out a card whose broadcast outcome was never seen`() =
        runTest {
            val fixture = fixture(card(fundingAttemptedAt = ATTEMPTED_AT))

            val item = collectState(fixture).items.single()

            // The money may already have gone, and then this link is the only route to it.
            assertEquals(GiftCardListStatus.UNRESOLVED, item.status)
            assertNotNull(item.onCopy)
            assertNotNull(item.onShare)
        }

    @Test
    fun `refuses to hand out a card that was already collected`() =
        runTest {
            val fixture = fixture(card(fundingTxid = TXID, status = GiftCardStatus.CLAIMED))

            val item = collectState(fixture).items.single()

            // Its link is spent; passing it on hands the recipient a dead card.
            assertEquals(GiftCardListStatus.CLAIMED, item.status)
            assertNull(item.onCopy)
            assertNull(item.onShare)
            // Nothing left to check either.
            assertNull(item.onCheck)
        }

    @Test
    fun `offers a collected check only once a card has been funded`() =
        runTest {
            val unfunded = fixture(card())
            assertNull(collectState(unfunded).items.single().onCheck)

            val funded = fixture(card(fundingTxid = TXID))
            assertNotNull(collectState(funded).items.single().onCheck)
        }

    @Test
    fun `lists gifts collected from other people`() =
        runTest {
            val fixture =
                fixture(
                    card = card(),
                    received =
                        listOf(
                            ReceivedGift(
                                address = "u1someoneelsesgiftcardaddress",
                                network = "main",
                                amountZatoshi = 50_000_000L,
                                claimedAt = "2026-08-21T09:00:00Z",
                                claimTxids = listOf(TXID),
                                message = "happy birthday",
                            )
                        ),
                )

            val received = collectState(fixture).received.single()

            assertEquals("happy birthday", received.message)
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
        received: List<ReceivedGift> = emptyList(),
    ): Fixture {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        return Fixture(card, storeThrows, received)
    }

    private fun TestScope.collectState(fixture: Fixture): GiftCardListState {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { fixture.vm.state.collect {} }
        advanceUntilIdle()
        return requireNotNull(fixture.vm.state.value)
    }

    private class Fixture(
        card: StoredGiftCard,
        storeThrows: Boolean,
        received: List<ReceivedGift> = emptyList(),
    ) {
        val copiedLink = slot<String>()
        val shareGiftLink = mockk<ShareGiftLinkUseCase>(relaxed = true)
        val checkGiftCardClaimed = mockk<CheckGiftCardClaimedUseCase>(relaxed = true)

        private val receivedStorage =
            mockk<ReceivedGiftStorageProvider>().also {
                every { it.observe() } returns MutableStateFlow(received)
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

        private val copyToClipboard =
            mockk<CopyToClipboardUseCase>().also {
                every { it.invoke(capture(copiedLink), any()) } returns Unit
            }

        val vm =
            GiftCardListVM(
                giftCardStorageProvider = storage,
                confirmGiftCardFunding = mockk<ConfirmGiftCardFundingUseCase>(relaxed = true),
                checkGiftCardClaimed = checkGiftCardClaimed,
                receivedGiftStorageProvider = receivedStorage,
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
            fundingTxid: String? = null,
            fundingAttemptedAt: String? = null,
            status: GiftCardStatus = GiftCardStatus.DRAFT,
        ) = StoredGiftCard(
            id = ID,
            network = "main",
            address = "u1exampleunifiedaddressforgiftcardtests",
            mnemonic = MNEMONIC,
            amountZatoshi = 100_000_000L,
            birthdayHeight = 2_800_000L,
            sourceAccountUuid = "account-uuid",
            createdAt = "2026-08-20T12:00:00Z",
            updatedAt = "2026-08-20T12:00:00Z",
            status = status,
            fundingTxid = fundingTxid,
            fundingAttemptedAt = fundingAttemptedAt,
        )
    }
}
