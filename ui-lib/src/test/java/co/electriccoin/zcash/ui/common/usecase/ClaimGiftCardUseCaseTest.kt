// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.GiftClaimDataSource
import co.electriccoin.zcash.ui.common.datasource.GiftClaimOutcome
import co.electriccoin.zcash.ui.common.model.UnifiedInfo
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.provider.GiftClaimOperationLock
import co.electriccoin.zcash.ui.common.provider.GiftKeyProvider
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.common.provider.ReceivedGiftStorageProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.provider.ZcashNetworkProvider
import co.electriccoin.zcash.ui.screen.gift.model.GiftLinkCodec
import co.electriccoin.zcash.ui.screen.gift.model.GiftLinkError
import co.electriccoin.zcash.ui.screen.gift.model.GiftLinkException
import co.electriccoin.zcash.ui.screen.gift.model.GiftLinkPayload
import co.electriccoin.zcash.ui.screen.gift.model.ReceivedGift
import io.mockk.coEvery
import io.mockk.coVerify
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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A claim with no reclaim has one unforgivable failure: moving the money and then saying it did
 * not. Both cases here are that failure arriving by the same route — an outcome dropped when the
 * app went to the background mid-broadcast — once as the receipt that never got written, and once
 * as what the recipient's next attempt is told.
 */
class ClaimGiftCardUseCaseTest {
    @Test
    fun `reads a card on a device that has no wallet yet`() =
        runTest {
            // The recipient's first-ever launch: they tapped the link before onboarding. Waiting on
            // a synchronizer here waits forever, and the spinner it leaves is the whole gift.
            val preview = previewUseCase(synchronizer = null).preview(GiftLinkCodec.encode(PAYLOAD))

            assertEquals(AMOUNT.toString(), preview.payload.amountZatoshi)
            assertEquals(ADDRESS, preview.cardAddress)
            // The one part of a preview a wallet is genuinely required for, so the card reads
            // without one and the scan cost waits.
            assertFalse(preview.hasWallet)
        }

    @Test
    fun `rejects a wrong-network card before there is a wallet to blame it on`() =
        runTest {
            val testnetCard = GiftLinkCodec.encode(PAYLOAD.copy(network = "test"))

            val error =
                assertFailsWith<GiftLinkException> {
                    previewUseCase(synchronizer = null).preview(testnetCard)
                }

            assertEquals(GiftLinkError.NETWORK_MISMATCH, error.error)
        }

    @Test
    fun `answers a link opened twice from the receipt, without scanning`() =
        runTest {
            // The second tap on a link this wallet already collected. A scan would spend thirty
            // seconds rediscovering an empty card; the receipt is the record that emptied it.
            val preview =
                previewUseCase(
                    synchronizer = null,
                    receipts = FakeReceipts(stored = listOf(RECEIPT)),
                ).preview(GiftLinkCodec.encode(PAYLOAD))

            assertEquals(
                GiftClaimOutcome.Claimed(amount = Zatoshi(AMOUNT), txIds = listOf(CLAIM_TXID)),
                preview.collected,
            )
        }

    @Test
    fun `still offers a claim whose broadcast has not been confirmed on chain`() =
        runTest {
            // The receipt exists but keeps its link, which is what an unconfirmed claim looks like.
            // Such a claim can still expire unmined, leaving the card funded — so this must stay
            // claimable rather than being retired as already collected.
            val unsettled = RECEIPT.copy(claimLink = PAYLOAD)

            val preview =
                previewUseCase(
                    synchronizer = null,
                    receipts = FakeReceipts(stored = listOf(unsettled)),
                ).preview(GiftLinkCodec.encode(PAYLOAD))

            assertNull(
                preview.collected,
                "an unconfirmed claim must not be reported as collected — the card may still hold its funds"
            )
        }

    @Test
    fun `leaves a card this wallet has not collected open to claim`() =
        runTest {
            val preview = previewUseCase(synchronizer = null).preview(GiftLinkCodec.encode(PAYLOAD))

            assertNull(preview.collected, "an uncollected card must not be reported as already claimed")
        }

    @Test
    fun `records the receipt even when the scope is cancelled while the claim runs`() =
        runTest {
            val receipts = FakeReceipts()
            var running: Job? = null
            // The broadcast half of a claim is already NonCancellable, so it reaches a verdict —
            // but it hands that verdict back into a context the app-lock may have cancelled while
            // it ran. Everything after it then throws, and this is the write that must not.
            val useCase = useCase(receipts, onClaim = { running?.cancel() })

            running = launch { runCatching { useCase(PAYLOAD, ADDRESS) {} } }
            running.join()

            assertEquals(listOf(emptyList(), RECEIPT.claimTxids), receipts.recorded.map { it.claimTxids })
            // The broadcast only reached the mempool, so the link has to survive the write.
            assertEquals(listOf(PAYLOAD, PAYLOAD), receipts.recorded.map { it.claimLink })
        }

    @Test
    fun `does not broadcast when the prepared receipt cannot be persisted`() =
        runTest {
            val receipts = mockk<ReceivedGiftStorageProvider>(relaxed = true)
            val dataSource = mockk<GiftClaimDataSource>(relaxed = true)
            coEvery { receipts.record(any()) } throws IllegalStateException("disk full")

            assertFailsWith<IllegalStateException> {
                useCase(receipts, dataSource = dataSource)(PAYLOAD, ADDRESS) {}
            }

            coVerify(exactly = 0) { dataSource.claim(any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `reports a card this wallet already collected as claimed, not empty`() =
        runTest {
            // What the cancelled claim above leaves behind: the money is here, the card is spent,
            // and the scan on the next attempt can only see an empty wallet. Telling the recipient
            // somebody else took it would be the one lie this screen must never tell.
            val receipts = FakeReceipts(stored = listOf(RECEIPT))
            val useCase = useCase(receipts, outcome = GiftClaimOutcome.Empty)

            val outcome = useCase(PAYLOAD, ADDRESS) {}

            assertEquals(GiftClaimOutcome.Claimed(amount = Zatoshi(AMOUNT), txIds = listOf(CLAIM_TXID)), outcome)
            assertTrue(
                receipts.recorded
                    .single()
                    .claimTxids
                    .isEmpty()
            )
        }

    @Test
    fun `still reports empty when the receipt belongs to another card`() =
        runTest {
            // Receipts are keyed on the card's address, and every card is its own wallet. A gift
            // collected last month must not make an unfunded card look collected.
            val receipts = FakeReceipts(stored = listOf(RECEIPT.copy(address = "u1someothercardentirely")))

            assertEquals(GiftClaimOutcome.Empty, useCase(receipts, GiftClaimOutcome.Empty)(PAYLOAD, ADDRESS) {})
        }

    @Test
    fun `fails closed when the receipts cannot be read`() =
        runTest {
            val receipts = FakeReceipts(readThrows = true)

            assertFailsWith<GiftReceiptStoreUnreadableException> {
                useCase(receipts, GiftClaimOutcome.Empty)(PAYLOAD, ADDRESS) {}
            }
        }

    @Test
    fun `keeps the link only until the claim is on chain`() =
        runTest {
            // A broadcast that reached the mempool can still expire unmined, and by then the card's
            // own wallet is deleted and the recipient may no longer have the link they opened. The
            // receipt is the only route left, so it holds the secret until the claim mines.
            val receipts = FakeReceipts()
            useCase(receipts)(PAYLOAD, ADDRESS) {}
            val held = receipts.recorded.last()

            assertEquals(PAYLOAD, held.claimLink)
            assertFalse(held.isSettled)
            assertTrue(held.copy(claimLink = null).isSettled)
        }

    @Test
    fun `persists the destination account and address before claiming`() =
        runTest {
            val receipts = FakeReceipts()

            useCase(receipts)(PAYLOAD, ADDRESS) {}

            val prepared = receipts.recorded.first()
            assertEquals(DESTINATION_ADDRESS, prepared.destinationAddress)
            assertEquals(DESTINATION_ACCOUNT_ID, prepared.destinationAccountUuid)
        }

    @Test
    fun `refuses a receipt holding a link for another network`() =
        runTest {
            // A link that cannot claim this card would send a retry at somebody else's money.
            assertFailsWith<IllegalArgumentException> {
                RECEIPT.copy(network = "test", claimLink = PAYLOAD)
            }
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

        override suspend fun settle(address: String) = Unit

        override suspend fun markFinalized(address: String) = Unit
    }

    /** No wallet means no synchronizer, so the build's own network is all a preview has to go on. */
    private fun previewUseCase(
        synchronizer: Synchronizer?,
        receipts: ReceivedGiftStorageProvider = mockk<ReceivedGiftStorageProvider>(relaxed = true),
    ) =
        ClaimGiftCardUseCase(
            accountDataSource = mockk<AccountDataSource>(relaxed = true),
            synchronizerProvider =
                mockk<SynchronizerProvider>(relaxed = true).also {
                    coEvery { it.getSynchronizerOrNull() } returns synchronizer
                },
            persistableWalletProvider = mockk<PersistableWalletProvider>(relaxed = true),
            giftKeyProvider =
                mockk<GiftKeyProvider>(relaxed = true).also {
                    coEvery { it.deriveAddress(any(), any()) } returns ADDRESS
                },
            giftClaimDataSource = mockk<GiftClaimDataSource>(relaxed = true),
            zcashNetworkProvider = mockk<ZcashNetworkProvider>().also { every { it() } returns ZcashNetwork.Mainnet },
            receivedGiftStorageProvider = receipts,
            giftClaimOperationLock = GiftClaimOperationLock(),
        )

    private fun useCase(
        receipts: ReceivedGiftStorageProvider,
        outcome: GiftClaimOutcome = GiftClaimOutcome.Claimed(amount = Zatoshi(AMOUNT), txIds = listOf(CLAIM_TXID)),
        onClaim: () -> Unit = {},
        dataSource: GiftClaimDataSource? = null,
    ) = ClaimGiftCardUseCase(
        accountDataSource = destinationAccountDataSource(),
        synchronizerProvider =
            mockk<SynchronizerProvider>(relaxed = true).also { provider ->
                coEvery { provider.getSynchronizer() } returns
                    mockk<Synchronizer>(relaxed = true).also { every { it.network } returns ZcashNetwork.Mainnet }
            },
        persistableWalletProvider = mockk<PersistableWalletProvider>(relaxed = true),
        giftKeyProvider = mockk<GiftKeyProvider>(relaxed = true),
        zcashNetworkProvider = mockk<ZcashNetworkProvider>(relaxed = true),
        giftClaimDataSource =
            dataSource ?: mockk<GiftClaimDataSource>(relaxed = true).also { source ->
                coEvery { source.claim(any(), any(), any(), any(), any(), any()) } coAnswers {
                    // Stands in for the app going to the background mid-broadcast: the claim itself
                    // finishes, and the context it returns into is already gone.
                    onClaim()
                    outcome
                }
            },
        receivedGiftStorageProvider = receipts,
        giftClaimOperationLock = GiftClaimOperationLock(),
    )

    private fun destinationAccountDataSource(): AccountDataSource {
        val address = mockk<WalletAddress.Unified>()
        every { address.address } returns DESTINATION_ADDRESS
        val unified = mockk<UnifiedInfo>()
        every { unified.address } returns address
        val sdkAccount = mockk<Account>()
        every { sdkAccount.accountUuid } returns AccountUuid.new(ByteArray(16))
        val account = mockk<WalletAccount>()
        every { account.unified } returns unified
        every { account.sdkAccount } returns sdkAccount
        return mockk<AccountDataSource>().also { coEvery { it.getSelectedAccount() } returns account }
    }

    private companion object {
        const val AMOUNT = 100_000_000L
        const val ADDRESS = "u1exampleunifiedaddressforgiftcardtests"
        const val CLAIM_TXID = "beef"
        const val DESTINATION_ADDRESS = "u1recipientwalletaddress"
        const val DESTINATION_ACCOUNT_ID = "00000000000000000000000000000000"

        val PAYLOAD =
            GiftLinkPayload(
                v = 1,
                network = "main",
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
