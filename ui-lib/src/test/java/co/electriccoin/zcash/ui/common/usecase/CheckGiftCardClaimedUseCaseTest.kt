// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.zcash.ui.common.datasource.GiftCardHoldings
import co.electriccoin.zcash.ui.common.datasource.GiftCardUnreachableException
import co.electriccoin.zcash.ui.common.datasource.GiftClaimDataSource
import co.electriccoin.zcash.ui.common.provider.GiftCardStorageProvider
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.screen.gift.model.GiftCardStatus
import co.electriccoin.zcash.ui.screen.gift.model.StoredGiftCard
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Settling a card is terminal and irreversible from the UI: a collected card cannot be handed out
 * again, cannot be re-checked, and no longer counts against a wallet reset. So the only question
 * this class really answers is what an empty wallet is allowed to mean.
 */
class CheckGiftCardClaimedUseCaseTest {
    @Test
    fun `reports collected only once the funding is known to have reached the card`() =
        runTest {
            val storage = storage()
            val useCase = useCase(storage, holdings(total = 0, hasFundingArrived = true))

            assertEquals(GiftCardCheckResult.COLLECTED, useCase(card()) {})

            coVerify(exactly = 1) { storage.markClaimed(id = ID, at = any()) }
        }

    @Test
    fun `refuses to settle a card whose funding never arrived`() =
        runTest {
            val storage = storage()
            val useCase = useCase(storage, holdings(total = 0, hasFundingArrived = false))

            // The wallet is empty because nothing was ever in it — the funding is in the mempool, or
            // was dropped and can still mine before it expires. Calling that collected settles the
            // card terminally and strands the money if it lands afterwards.
            assertEquals(GiftCardCheckResult.FUNDING_PENDING, useCase(card()) {})

            coVerify(exactly = 0) { storage.markClaimed(any(), any()) }
            // Nor is it a check that found the funds still there.
            coVerify(exactly = 0) { storage.recordChecked(any(), any()) }
        }

    @Test
    fun `settles a card shared before its funding mined`() =
        runTest {
            val storage = storage()
            val useCase = useCase(storage, holdings(total = 0, hasFundingArrived = true))

            // The status cannot say this card's funding mined — sharing outranks funded — so the
            // evidence has to come from the card's own wallet, or a legitimately collected card
            // could never be settled.
            val card = card(status = GiftCardStatus.SHARED)

            assertEquals(GiftCardCheckResult.COLLECTED, useCase(card) {})
            coVerify(exactly = 1) { storage.markClaimed(id = ID, at = any()) }
        }

    @Test
    fun `reports the funds still sitting on the card`() =
        runTest {
            val storage = storage()
            val useCase = useCase(storage, holdings(total = 100_000_000L, hasFundingArrived = true))

            assertEquals(GiftCardCheckResult.WAITING, useCase(card()) {})

            coVerify(exactly = 1) { storage.recordChecked(id = ID, at = any()) }
            coVerify(exactly = 0) { storage.markClaimed(any(), any()) }
        }

    @Test
    fun `never scans a card that was never funded`() =
        runTest {
            val dataSource = mockk<GiftClaimDataSource>(relaxed = true)
            val useCase = useCase(storage(), dataSource = dataSource)

            assertEquals(GiftCardCheckResult.NOT_FUNDED, useCase(card(txid = null)) {})

            coVerify(exactly = 0) { dataSource.inspect(any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `scans for the card's own funding transaction, not for any mined transaction`() =
        runTest {
            val dataSource =
                mockk<GiftClaimDataSource>(relaxed = true).also {
                    coEvery { it.inspect(any(), any(), any(), any(), any(), any()) } returns
                        holdings(total = 0, hasFundingArrived = true)
                }
            val useCase = useCase(storage(), dataSource = dataSource)

            useCase(card()) {}

            // The card's address is plaintext in its link, so anybody can send to it, and a
            // transparent send mines into the card wallet's history while leaving the shielded
            // balance at zero. That pair is "collected" to a scan that accepts any mined
            // transaction — and settling is terminal.
            coVerify(exactly = 1) { dataSource.inspect(any(), any(), any(), any(), fundingTxid = TXID, any()) }
        }

    @Test
    fun `separates an unreachable server from a scan that went wrong`() =
        runTest {
            val storage = storage()

            // Neither says anything about the card, and the two need different copy.
            assertEquals(
                GiftCardCheckResult.UNREACHABLE,
                useCase(storage, throws = GiftCardUnreachableException())(card()) {}
            )
            assertEquals(
                GiftCardCheckResult.UNKNOWN,
                useCase(storage, throws = IllegalStateException("scan died"))(card()) {}
            )
            coVerify(exactly = 0) { storage.markClaimed(any(), any()) }
        }

    private fun holdings(total: Long, hasFundingArrived: Boolean) =
        GiftCardHoldings(
            available = Zatoshi(total),
            total = Zatoshi(total),
            hasFundingArrived = hasFundingArrived,
        )

    private fun storage() = mockk<GiftCardStorageProvider>(relaxed = true)

    private fun useCase(
        storage: GiftCardStorageProvider,
        holdings: GiftCardHoldings? = null,
        throws: Throwable? = null,
        dataSource: GiftClaimDataSource =
            mockk<GiftClaimDataSource>(relaxed = true).also { source ->
                if (throws != null) {
                    coEvery { source.inspect(any(), any(), any(), any(), any(), any()) } throws throws
                } else if (holdings != null) {
                    coEvery { source.inspect(any(), any(), any(), any(), any(), any()) } returns holdings
                }
            },
    ) = CheckGiftCardClaimedUseCase(
        synchronizerProvider =
            mockk<SynchronizerProvider>(relaxed = true).also { provider ->
                coEvery { provider.getSynchronizer() } returns
                    mockk<Synchronizer>(relaxed = true).also { every { it.network } returns ZcashNetwork.Mainnet }
            },
        persistableWalletProvider = mockk<PersistableWalletProvider>(relaxed = true),
        giftClaimDataSource = dataSource,
        giftCardStorageProvider = storage,
    )

    private fun card(
        txid: String? = TXID,
        status: GiftCardStatus = GiftCardStatus.DRAFT,
    ) = StoredGiftCard(
        id = ID,
        network = "main",
        address = "u1exampleunifiedaddressforgiftcardtests",
        mnemonic = MNEMONIC,
        amountZatoshi = 100_000_000L,
        birthdayHeight = 2_800_000L,
        sourceAccountUuid = "account-1",
        createdAt = NOW,
        updatedAt = NOW,
        status = status,
        fundingTxid = txid,
    )

    private companion object {
        const val ID = "card-1"
        const val TXID = "f00d"
        const val NOW = "2026-08-20T12:00:00Z"

        /** BIP-39 test vector for all-zero entropy. Never a real wallet. */
        const val MNEMONIC =
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon art"
    }
}
