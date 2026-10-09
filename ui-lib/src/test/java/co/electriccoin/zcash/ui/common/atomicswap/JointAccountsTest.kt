// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.atomicswap

import cash.z.ecc.android.sdk.Broadcaster
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.AccountBalance
import cash.z.ecc.android.sdk.model.AccountImportSetup
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.CreatedTransaction
import cash.z.ecc.android.sdk.model.FirstClassByteArray
import cash.z.ecc.android.sdk.model.Pczt
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.TransactionId
import cash.z.ecc.android.sdk.model.TransactionOverview
import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.datasource.ATOMIC_SWAP_KEYSOURCE
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import xyz.justzappit.atomicswap.SwapKey
import xyz.justzappit.offramp.atomicswap.SWAP_SHARE_BYTES
import xyz.justzappit.offramp.atomicswap.SwapShare
import xyz.justzappit.offramp.atomicswap.ZcashTransaction
import xyz.justzappit.offramp.atomicswap.ZcashTxId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class JointAccountsTest {
    private val synchronizer = mockk<Synchronizer>()
    private val synchronizers = mockk<SynchronizerProvider>()
    private val keys = mockk<SwapKeyring>()
    private val transactions = mockk<SwapZcashTransactions>()
    private var created = 0
    private val jointAccounts =
        JointAccounts(
            synchronizers = synchronizers,
            accounts = mockk<AccountDataSource> { coEvery { getZashiAccount().unified.address.address } returns HOME },
            transactions = transactions,
            keys = keys,
        )

    init {
        coEvery { synchronizers.getSynchronizer() } returns synchronizer
        coEvery { keys.withKey(any(), any<(SwapKey) -> String>()) } answers { ufvk(firstArg()) }
        coEvery { transactions.synced() } returns synchronizer
        every { synchronizer.fullyScannedHeight } returns MutableStateFlow(BlockHeight.new(SCANNED))
        every { synchronizer.networkHeight } returns MutableStateFlow(BlockHeight.new(SCANNED))
    }

    @Test
    fun `a swap's joint account is the one its key makes, among the accounts swaps imported`() =
        runTest {
            val joint = account(ufvk(1))
            coEvery { synchronizer.getAccounts() } returns
                listOf(account(ufvk(1), source = null), joint, account(ufvk(2)))

            assertSame(joint, jointAccounts.find(1, MAKER_SHARE))
            assertNull(jointAccounts.find(3, MAKER_SHARE))
        }

    @Test
    fun `a joint account is imported once, from the swap's birthday`() =
        runTest {
            val joint = account(ufvk(1))
            val setup = slot<AccountImportSetup>()
            coEvery { synchronizer.getAccounts() } returns emptyList()
            coEvery { synchronizer.importAccountByUfvk(capture(setup)) } returns joint

            assertSame(joint, jointAccounts.import(1, MAKER_SHARE, birthday = BIRTHDAY))
            coEvery { synchronizer.getAccounts() } returns listOf(joint)
            assertSame(joint, jointAccounts.import(1, MAKER_SHARE, birthday = BIRTHDAY))

            coVerify(exactly = 1) { synchronizer.importAccountByUfvk(any()) }
            assertEquals(ATOMIC_SWAP_KEYSOURCE, setup.captured.keySource)
            assertEquals(ufvk(1), setup.captured.ufvk.encoding)
            assertEquals(BlockHeight.new(BIRTHDAY), setup.captured.birthday)
        }

    @Test
    fun `a sweep made before an interruption is taken up again without signing another`() =
        runTest {
            val joint = account(ufvk(1))
            every { synchronizer.walletBalances } returns balances(joint, available = 0, pending = 0)
            coEvery { synchronizer.getTransactions(joint.accountUuid) } returns
                flowOf(listOf(sent(expiry = SCANNED + 40)))

            val sweep = checkNotNull(jointAccounts.sweep(joint) { _, _ -> error("signed a second sweep") })

            assertEquals(ZcashTransaction(ZcashTxId.parse(TX), "0102", SCANNED + 40), sweep.transaction)
            assertEquals(190_000, sweep.receivedZat)
            assertEquals(10_000, sweep.feeZat)
        }

    @Test
    fun `a sweep that expired is made again, once every note of the balance is spendable`() =
        runTest {
            val joint = account(ufvk(1))
            val signedPczt = slot<Pczt>()
            val broadcaster = mockk<Broadcaster>()
            var signatures = 0
            coEvery { synchronizer.getTransactions(joint.accountUuid) } returns flowOf(listOf(sent(expiry = SCANNED)))
            every { synchronizer.walletBalances } returns balances(joint, available = 200_000, pending = 50_000)

            assertNull(jointAccounts.sweep(joint) { _, _ -> error("signed with notes pending") })

            every { synchronizer.walletBalances } returns balances(joint, available = 250_000, pending = 0)
            coEvery { synchronizer.proposeTransfer(joint, HOME, Zatoshi(240_000), any()) } returns proposal(10_000)
            coEvery { synchronizer.createPcztFromProposal(joint.accountUuid, any()) } returns Pczt(byteArrayOf(1))
            coEvery { synchronizer.addProofsToPczt(any()) } returns Pczt(byteArrayOf(2))
            coEvery { synchronizer.redactPcztForSigner(any()) } returns Pczt(byteArrayOf(3))
            every { synchronizer.broadcaster } returns broadcaster
            coEvery { broadcaster.createTransactionFromPczt(any(), capture(signedPczt)) } returns
                listOf(
                    CreatedTransaction(
                        FirstClassByteArray(ByteArray(32) { 0xcd.toByte() }),
                        FirstClassByteArray(byteArrayOf(4)),
                        BlockHeight.new(SCANNED + 40)
                    )
                )

            val sweep =
                checkNotNull(
                    jointAccounts.sweep(joint) { pczt, intent ->
                        assertEquals(HOME, intent.recipient)
                        assertEquals(1, intent.minimumReceivedZat)
                        assertEquals(249_999, intent.maximumFeeZat)
                        signatures++
                        pczt + 5
                    }
                )

            assertEquals(1, signatures)
            assertContentEquals(byteArrayOf(1, 5), signedPczt.captured.toByteArray())
            assertEquals(ZcashTransaction(ZcashTxId.parse("cd".repeat(32)), "04", SCANNED + 40), sweep.transaction)
            assertEquals(240_000, sweep.receivedZat)
            assertEquals(10_000, sweep.feeZat)
        }

    @Test
    fun `forgetting a joint account outlasts the wallet failing, but not cancellation`() =
        runTest {
            val joint = account(ufvk(1))
            coEvery { synchronizer.getAccounts() } returns listOf(joint)
            coEvery { synchronizer.deleteAccount(joint.accountUuid) } throws IllegalStateException("database busy")

            jointAccounts.forget(1, MAKER_SHARE)
            jointAccounts.forget(2, MAKER_SHARE)
            coEvery { synchronizer.deleteAccount(joint.accountUuid) } throws CancellationException("left the screen")

            assertFailsWith<CancellationException> { jointAccounts.forget(1, MAKER_SHARE) }
            coVerify(exactly = 2) { synchronizer.deleteAccount(any()) }
        }

    private fun account(
        viewingKey: String,
        source: String? = ATOMIC_SWAP_KEYSOURCE,
    ): Account {
        val id = ++created
        return mockk {
            every { accountUuid } returns AccountUuid.new(ByteArray(16) { id.toByte() })
            every { ufvk } returns viewingKey
            every { keySource } returns source
        }
    }

    private fun balances(
        account: Account,
        available: Long,
        pending: Long,
    ) = MutableStateFlow(
        mapOf(
            account.accountUuid to
                AccountBalance(
                    sapling = pool(0, 0),
                    orchard = pool(available / 2, pending),
                    ironwood = pool(available - available / 2, 0),
                    unshielded = Zatoshi(0),
                )
        )
    )

    private fun pool(
        available: Long,
        pending: Long
    ) = WalletBalance(Zatoshi(available), Zatoshi(0), Zatoshi(pending))

    private fun sent(expiry: Long): TransactionOverview =
        mockk {
            every { txId } returns TransactionId.new(TX)
            every { isSentTransaction } returns true
            every { minedHeight } returns null
            every { expiryHeight } returns BlockHeight.new(expiry)
            every { raw } returns FirstClassByteArray(byteArrayOf(1, 2))
            every { netValue } returns Zatoshi(200_000)
            every { feePaid } returns Zatoshi(10_000)
        }

    private fun proposal(fee: Long): Proposal =
        mockk {
            every { totalFeeRequired() } returns Zatoshi(fee)
            every { transactionCount() } returns 1
        }

    private companion object {
        const val HOME = "u1home"
        const val SCANNED = 3_200_000L
        const val BIRTHDAY = 3_100_000L
        val TX = "ab".repeat(32)
        val MAKER_SHARE = SwapShare.of(ByteArray(SWAP_SHARE_BYTES) { 2 })

        fun ufvk(index: Int) = "uviewtest1joint$index"
    }
}
