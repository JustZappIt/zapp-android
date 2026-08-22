// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.GiftClaimDataSource
import co.electriccoin.zcash.ui.common.datasource.GiftClaimOutcome
import co.electriccoin.zcash.ui.common.provider.GiftKeyProvider
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.common.provider.ReceivedGiftStorageProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.screen.gift.model.GiftLinkPayload
import co.electriccoin.zcash.ui.screen.gift.model.ReceivedGift
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A claim with no reclaim has one unforgivable failure: moving the money and then saying it did
 * not. Both cases here are that failure arriving by the same route — an outcome dropped when the
 * app went to the background mid-broadcast — once as the receipt that never got written, and once
 * as what the recipient's next attempt is told.
 */
class ClaimGiftCardUseCaseTest {
    @Test
    fun `records the receipt even when the scope is cancelled while the claim runs`() =
        runTest {
            val receipts = FakeReceipts()
            var running: Job? = null
            // The broadcast half of a claim is already NonCancellable, so it reaches a verdict —
            // but it hands that verdict back into a context the app-lock may have cancelled while
            // it ran. Everything after it then throws, and this is the write that must not.
            val useCase = useCase(receipts, onClaim = { running?.cancel() })

            running = launch { runCatching { useCase(PAYLOAD) {} } }
            running.join()

            assertEquals(listOf(RECEIPT.claimTxids), receipts.recorded.map { it.claimTxids })
        }

    @Test
    fun `reports a card this wallet already collected as claimed, not empty`() =
        runTest {
            // What the cancelled claim above leaves behind: the money is here, the card is spent,
            // and the scan on the next attempt can only see an empty wallet. Telling the recipient
            // somebody else took it would be the one lie this screen must never tell.
            val receipts = FakeReceipts(stored = listOf(RECEIPT))
            val useCase = useCase(receipts, outcome = GiftClaimOutcome.Empty)

            val outcome = useCase(PAYLOAD) {}

            assertEquals(GiftClaimOutcome.Claimed(amount = Zatoshi(AMOUNT), txIds = listOf(CLAIM_TXID)), outcome)
            // Read back, not re-recorded: the receipt is a note about when it happened.
            assertTrue(receipts.recorded.isEmpty())
        }

    @Test
    fun `still reports empty when the receipt belongs to another card`() =
        runTest {
            // Receipts are keyed on the card's address, and every card is its own wallet. A gift
            // collected last month must not make an unfunded card look collected.
            val receipts = FakeReceipts(stored = listOf(RECEIPT.copy(address = "u1someothercardentirely")))

            assertEquals(GiftClaimOutcome.Empty, useCase(receipts, GiftClaimOutcome.Empty)(PAYLOAD) {})
        }

    @Test
    fun `still reports empty when the receipts cannot be read`() =
        runTest {
            // Losing the receipt store loses history, never money. It must not turn a claim into a
            // crash — this falls back to what the scan itself found.
            val receipts = FakeReceipts(readThrows = true)

            assertEquals(GiftClaimOutcome.Empty, useCase(receipts, GiftClaimOutcome.Empty)(PAYLOAD) {})
        }

    /**
     * A store whose write really suspends, which is the point of it being a fake rather than a
     * mock. Cancellation is only observable at a suspension point, so a stub that returns without
     * one cannot tell a protected write from an unprotected one — it records the call either way.
     */
    private class FakeReceipts(
        private val stored: List<ReceivedGift> = emptyList(),
        private val readThrows: Boolean = false,
    ) : ReceivedGiftStorageProvider {
        val recorded = mutableListOf<ReceivedGift>()

        override fun observe(): Flow<List<ReceivedGift>> = flowOf(stored)

        override suspend fun getAll(): List<ReceivedGift> {
            check(!readThrows) { "store is unreadable" }
            return stored
        }

        override suspend fun record(gift: ReceivedGift) {
            yield()
            recorded += gift
        }
    }

    private fun useCase(
        receipts: ReceivedGiftStorageProvider,
        outcome: GiftClaimOutcome = GiftClaimOutcome.Claimed(amount = Zatoshi(AMOUNT), txIds = listOf(CLAIM_TXID)),
        onClaim: () -> Unit = {},
    ) = ClaimGiftCardUseCase(
        accountDataSource = mockk<AccountDataSource>(relaxed = true),
        synchronizerProvider =
            mockk<SynchronizerProvider>(relaxed = true).also { provider ->
                coEvery { provider.getSynchronizer() } returns
                    mockk<Synchronizer>(relaxed = true).also { every { it.network } returns ZcashNetwork.Mainnet }
            },
        persistableWalletProvider = mockk<PersistableWalletProvider>(relaxed = true),
        giftKeyProvider = mockk<GiftKeyProvider>(relaxed = true),
        giftClaimDataSource =
            mockk<GiftClaimDataSource>(relaxed = true).also { source ->
                coEvery { source.claim(any(), any(), any(), any(), any()) } coAnswers {
                    // Stands in for the app going to the background mid-broadcast: the claim itself
                    // finishes, and the context it returns into is already gone.
                    onClaim()
                    outcome
                }
            },
        receivedGiftStorageProvider = receipts,
    )

    private companion object {
        const val AMOUNT = 100_000_000L
        const val ADDRESS = "u1exampleunifiedaddressforgiftcardtests"
        const val CLAIM_TXID = "beef"

        val PAYLOAD =
            GiftLinkPayload(
                v = 1,
                network = "main",
                address = ADDRESS,
                amountZatoshi = AMOUNT.toString(),
                // BIP-39 test vector for all-zero entropy. Never a real wallet.
                mnemonic =
                    "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon " +
                        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon " +
                        "abandon abandon abandon art",
                birthdayHeight = 2_800_000L,
                createdAt = "2026-08-20T12:00:00Z",
            )

        val RECEIPT =
            ReceivedGift(
                address = ADDRESS,
                network = "main",
                amountZatoshi = AMOUNT,
                claimedAt = "2026-08-20T12:05:00Z",
                claimTxids = listOf(CLAIM_TXID),
            )
    }
}
