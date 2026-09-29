// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.atomicswap

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.AccountImportSetup
import cash.z.ecc.android.sdk.model.AccountPurpose
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.CreatedTransaction
import cash.z.ecc.android.sdk.model.FirstClassByteArray
import cash.z.ecc.android.sdk.model.Pczt
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.TransactionState
import cash.z.ecc.android.sdk.model.UnifiedFullViewingKey
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.Zip32AccountIndex
import co.electriccoin.zcash.ui.common.datasource.ATOMIC_SWAP_KEYSOURCE
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import xyz.justzappit.atomicswap.AtomicSwap
import xyz.justzappit.atomicswap.ReverseAtomicSwap
import xyz.justzappit.evm.util.hexToBytes
import xyz.justzappit.evm.util.toHex
import xyz.justzappit.offramp.atomicswap.ReverseReceiveEstimate
import xyz.justzappit.offramp.atomicswap.ReverseReceiveStatus
import xyz.justzappit.offramp.atomicswap.ReverseReceiveTransaction
import xyz.justzappit.offramp.atomicswap.ReverseSwapRecord
import xyz.justzappit.offramp.atomicswap.ReverseSwapZcash
import xyz.justzappit.offramp.atomicswap.ReverseTransactionStatus
import xyz.justzappit.offramp.atomicswap.SWAP_SHARE_BYTES
import xyz.justzappit.offramp.atomicswap.fixedHex
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ReverseSwapZcashImpl(
    private val synchronizers: SynchronizerProvider,
    private val accounts: AccountDataSource,
    private val wallet: PersistableWalletProvider,
    private val keys: AtomicSwapKeysImpl,
) : ReverseSwapZcash {
    override suspend fun height(): Long =
        withTimeout(30.seconds) {
            synchronizers
                .getSynchronizer()
                .networkHeight
                .filterNotNull()
                .first()
                .value
        }

    override suspend fun importAccount(record: ReverseSwapRecord): String {
        val synchronizer = synchronizers.getSynchronizer()
        val ufvk = viewingKey(record)
        val account =
            synchronizer.getAccounts().firstOrNull { it.ufvk == ufvk && it.keySource == ATOMIC_SWAP_KEYSOURCE }
                ?: synchronizer.importAccountByUfvk(
                    AccountImportSetup(
                        accountName = "Swap deposit",
                        keySource = ATOMIC_SWAP_KEYSOURCE,
                        purpose = AccountPurpose.Spending(ByteArray(0), Zip32AccountIndex.new(0)),
                        ufvk = UnifiedFullViewingKey(ufvk),
                        birthday = BlockHeight.new(record.birthday),
                    )
                )
        return account.accountUuid.value.toHex()
    }

    override suspend fun spendable(record: ReverseSwapRecord): Long {
        val synchronizer = synchronizers.getSynchronizer()
        val account = account(record)
        check(synchronizer.syncToTip(2.minutes) == Synchronizer.SyncBurstResult.SYNCED_TO_TIP)
        val balance = synchronizer.walletBalances.value?.get(account.accountUuid) ?: return 0
        return (balance.orchard.available + balance.ironwood.available).value
    }

    override suspend fun prepareReceive(record: ReverseSwapRecord, makerSecret: ByteArray): ReverseReceiveTransaction {
        val synchronizer = synchronizers.getSynchronizer()
        val account = account(record)
        check(synchronizer.syncToTip(2.minutes) == Synchronizer.SyncBurstResult.SYNCED_TO_TIP)
        val earlier =
            synchronizer
                .getTransactions(account.accountUuid)
                .first()
                .firstOrNull { it.isSentTransaction && it.transactionState != TransactionState.Expired }
        if (earlier != null) {
            return ReverseReceiveTransaction(
                earlier.txId.txIdString(),
                checkNotNull(earlier.raw).byteArray.toHex(),
                checkNotNull(earlier.expiryHeight).value,
                earlier.netValue.value - checkNotNull(earlier.feePaid).value,
                checkNotNull(earlier.feePaid).value,
            )
        }
        val (available, proposal) = receiveProposal(record)
        val fee = proposal.totalFeeRequired()
        val pczt = synchronizer.createPcztFromProposal(account.accountUuid, proposal)
        val proved = synchronizer.addProofsToPczt(pczt.clonePczt())
        val redacted = synchronizer.redactPcztForSigner(pczt.clonePczt())
        val signed =
            keys.withKey(record.index) {
                ReverseAtomicSwap.signReceive(
                    it,
                    fixedHex(record.quote.terms.makerShare, SWAP_SHARE_BYTES),
                    makerSecret,
                    redacted.toByteArray()
                )
            }
        val transaction = synchronizer.broadcaster.createTransactionFromPczt(proved, Pczt(signed)).single()
        return ReverseReceiveTransaction(
            transaction.txIdString(),
            transaction.raw.byteArray.toHex(),
            checkNotNull(transaction.expiryHeight).value,
            available - fee.value,
            fee.value
        )
    }

    override suspend fun estimateReceive(record: ReverseSwapRecord): ReverseReceiveEstimate {
        val (available, proposal) = receiveProposal(record)
        return ReverseReceiveEstimate(available, proposal.totalFeeRequired().value)
    }

    private suspend fun receiveProposal(record: ReverseSwapRecord): Pair<Long, Proposal> {
        val synchronizer = synchronizers.getSynchronizer()
        val account = account(record)
        val available = spendable(record)
        check(available >= record.quote.terms.depositZat)
        val home =
            accounts
                .getZashiAccount()
                .unified.address.address
        check(available > MIN_SWEEP_FEE)
        var fee = Zatoshi(MIN_SWEEP_FEE)
        var proposal = synchronizer.proposeTransfer(account, home, Zatoshi(available) - fee)
        if (proposal.totalFeeRequired() != fee) {
            fee = proposal.totalFeeRequired()
            proposal = synchronizer.proposeTransfer(account, home, Zatoshi(available) - fee)
        }
        check(proposal.transactionCount() == 1 && proposal.totalFeeRequired() == fee)
        return available to proposal
    }

    override suspend fun submit(transaction: ReverseReceiveTransaction) {
        synchronizers.getSynchronizer().broadcaster.submit(
            CreatedTransaction(
                FirstClassByteArray(transaction.txId.hexToBytes().reversedArray()),
                FirstClassByteArray(transaction.raw.hexToBytes()),
                BlockHeight.new(transaction.expiryHeight)
            ),
            wallet.requirePersistableWallet().endpoint,
        )
    }

    override suspend fun receiveStatus(record: ReverseSwapRecord): ReverseReceiveStatus {
        val synchronizer = synchronizers.getSynchronizer()
        check(synchronizer.syncToTip(2.minutes) == Synchronizer.SyncBurstResult.SYNCED_TO_TIP)
        val transaction =
            synchronizer
                .getTransactions(account(record).accountUuid)
                .first()
                .firstOrNull { it.txId.txIdString() == record.receive?.txId }
                ?: return ReverseReceiveStatus(ReverseTransactionStatus.UNKNOWN)
        val confirmations =
            transaction.minedHeight?.let { mined ->
                synchronizer.networkHeight.value?.let { tip -> (tip.value - mined.value + 1).coerceAtLeast(0) }
            } ?: 0
        val status =
            when (transaction.transactionState) {
                TransactionState.Confirmed -> ReverseTransactionStatus.CONFIRMED
                TransactionState.Pending -> ReverseTransactionStatus.PENDING
                TransactionState.Expired -> ReverseTransactionStatus.EXPIRED
            }
        return ReverseReceiveStatus(status, confirmations)
    }

    private suspend fun account(record: ReverseSwapRecord): Account {
        val ufvk = viewingKey(record)
        return checkNotNull(
            synchronizers.getSynchronizer().getAccounts().firstOrNull {
                it.accountUuid.value.toHex() == record.account && it.ufvk == ufvk &&
                    it.keySource == ATOMIC_SWAP_KEYSOURCE
            }
        ) { "joint account does not match this swap" }
    }

    private suspend fun viewingKey(record: ReverseSwapRecord): String =
        keys.withKey(record.index) {
            AtomicSwap.depositAccount(it, fixedHex(record.quote.terms.makerShare, SWAP_SHARE_BYTES)).ufvk
        }

    private companion object {
        const val MIN_SWEEP_FEE = 10_000L
    }
}
