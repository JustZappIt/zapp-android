// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.atomicswap

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.AccountImportSetup
import cash.z.ecc.android.sdk.model.AccountPurpose
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.Pczt
import cash.z.ecc.android.sdk.model.TransactionState
import cash.z.ecc.android.sdk.model.TransactionSubmitResult
import cash.z.ecc.android.sdk.model.UnifiedFullViewingKey
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.Zip32AccountIndex
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.datasource.ATOMIC_SWAP_KEYSOURCE
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.datasource.ZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import xyz.justzappit.atomicswap.AtomicSwap
import xyz.justzappit.offramp.atomicswap.AtomicSwapZcash
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** [AtomicSwapZcash] over the wallet's SDK. */
class AtomicSwapZcashImpl(
    private val synchronizerProvider: SynchronizerProvider,
    private val accountDataSource: AccountDataSource,
    private val proposalDataSource: ProposalDataSource,
    private val zashiSpendingKeyDataSource: ZashiSpendingKeyDataSource,
    private val persistableWalletProvider: PersistableWalletProvider,
    private val keys: AtomicSwapKeysImpl,
    private val store: AtomicSwapStoreImpl,
) : AtomicSwapZcash {
    override suspend fun chainHeight(): Long =
        withTimeout(HEIGHT_TIMEOUT) {
            synchronizerProvider
                .getSynchronizer()
                .networkHeight
                .filterNotNull()
                .first()
        }.value

    override suspend fun pay(
        address: String,
        zatoshi: Long
    ): String {
        val synchronizer = synchronizerProvider.getSynchronizer()
        val proposal =
            synchronizer.proposeTransfer(accountDataSource.getZashiAccount().sdkAccount, address, Zatoshi(zatoshi))
        check(proposal.transactionCount() == 1) { "the deposit would take more than one transaction" }
        store.active()?.maxTotalZat?.let { maximum ->
            check(zatoshi <= maximum - proposal.totalFeeRequired().value) { "deposit exceeds authorized amount" }
        }
        val usk = zashiSpendingKeyDataSource.getZashiSpendingKey()
        val endpoint = persistableWalletProvider.requirePersistableWallet().endpoint
        // Once created, the transaction may be sent by the wallet on its own, so its id is the answer
        // whatever the submission reports.
        return withContext(NonCancellable) {
            val transaction = proposalDataSource.createTransactions(proposal, usk).single()
            val result = proposalDataSource.submitTransaction(transaction, endpoint)
            if (result !is SubmitResult.Success) {
                Twig.warn {
                    "Atomic swap deposit ${transaction.txIdString()} was created; its submission reported $result"
                }
            }
            transaction.txIdString()
        }
    }

    override suspend fun findPayment(address: String): String? {
        val synchronizer = synchronizerProvider.getSynchronizer()
        return synchronizer
            .getTransactions(accountDataSource.getZashiAccount().sdkAccount.accountUuid)
            .first()
            .filter { it.isSentTransaction && it.transactionState != TransactionState.Expired }
            .firstOrNull { sent -> synchronizer.getRecipients(sent).toList().any { it.addressValue == address } }
            ?.txId
            ?.txIdString()
    }

    override suspend fun sweepRefund(
        index: Int,
        makerShare: ByteArray,
        makerSecret: ByteArray,
        birthday: Long,
    ): String {
        val synchronizer = synchronizerProvider.getSynchronizer()
        val ufvk = keys.withKey(index) { AtomicSwap.depositAccount(it, makerShare).ufvk }
        val account =
            synchronizer.getAccounts().firstOrNull { it.keySource == ATOMIC_SWAP_KEYSOURCE && it.ufvk == ufvk }
                ?: importDeposit(synchronizer, ufvk, birthday)
        val txId = earlierSweep(synchronizer, account) ?: sweep(synchronizer, account, index, makerShare, makerSecret)
        check(synchronizer.deleteAccount(account.accountUuid)) { "the SDK kept the deposit account" }
        return txId
    }

    /** A sweep sent before an interruption: the deposit account never sends anything else. */
    private suspend fun earlierSweep(
        synchronizer: Synchronizer,
        account: Account
    ): String? =
        synchronizer
            .getTransactions(account.accountUuid)
            .first()
            .firstOrNull { it.isSentTransaction && it.transactionState != TransactionState.Expired }
            ?.txId
            ?.txIdString()

    private suspend fun importDeposit(
        synchronizer: Synchronizer,
        ufvk: String,
        birthday: Long
    ): Account =
        synchronizer.importAccountByUfvk(
            AccountImportSetup(
                accountName = "Swap deposit",
                keySource = ATOMIC_SWAP_KEYSOURCE,
                // An empty fingerprint imports a spending account with no derivation, the way zecSwap's
                // own wallet does: the SDK then builds spends it can't sign, and libzecswap signs them.
                purpose = AccountPurpose.Spending(ByteArray(0), Zip32AccountIndex.new(0)),
                ufvk = UnifiedFullViewingKey(ufvk),
                birthday = BlockHeight.new(birthday),
            )
        )

    /** Sends the deposit account's whole balance home, less the fee, with no change. */
    private suspend fun sweep(
        synchronizer: Synchronizer,
        account: Account,
        index: Int,
        makerShare: ByteArray,
        makerSecret: ByteArray,
    ): String {
        val spendable = awaitSpendable(synchronizer, account)
        val home =
            accountDataSource
                .getZashiAccount()
                .unified.address.address
        var fee = Zatoshi(ZIP317_TWO_ACTIONS)
        var proposal = synchronizer.proposeTransfer(account, home, spendable - fee)
        if (proposal.totalFeeRequired() != fee) {
            fee = proposal.totalFeeRequired()
            proposal = synchronizer.proposeTransfer(account, home, spendable - fee)
        }
        val pczt = synchronizer.createPcztFromProposal(account.accountUuid, proposal)
        val withProofs = synchronizer.addProofsToPczt(pczt.clonePczt())
        val redacted = synchronizer.redactPcztForSigner(pczt.clonePczt())
        val signed = keys.withKey(index) { AtomicSwap.signRefund(it, makerShare, makerSecret, redacted.toByteArray()) }
        val result = synchronizer.createTransactionFromPczt(withProofs, Pczt(signed)).toList().single()
        check(result is TransactionSubmitResult.Success) { "the refund sweep was not accepted: $result" }
        return result.txIdString()
    }

    /**
     * Waits for the imported account to find the deposit and for every note of it to confirm. The
     * engine stands still while the app is in the background, so each look asks for a sync first.
     */
    private suspend fun awaitSpendable(
        synchronizer: Synchronizer,
        account: Account
    ): Zatoshi =
        withTimeout(SPENDABLE_TIMEOUT) {
            var spendable = spendable(synchronizer, account)
            while (spendable == null) {
                synchronizer.syncToTip(SYNC_BURST_TIMEOUT)
                delay(SPENDABLE_POLL)
                spendable = spendable(synchronizer, account)
            }
            spendable
        }

    /** The account's balance once every note of it has confirmed; null until then. */
    private fun spendable(
        synchronizer: Synchronizer,
        account: Account
    ): Zatoshi? {
        val balance = synchronizer.walletBalances.value?.get(account.accountUuid) ?: return null
        val available = balance.orchard.available + balance.ironwood.available
        val pending = balance.orchard.valuePending + balance.ironwood.valuePending
        return available.takeIf { it.value > 0 && pending.value == 0L }
    }

    private companion object {
        // ZIP 317: 5,000 zatoshi per logical action, two at least; one deposit note home is two.
        const val ZIP317_TWO_ACTIONS = 10_000L
        val HEIGHT_TIMEOUT = 30.seconds
        val SPENDABLE_TIMEOUT = 45.minutes
        val SPENDABLE_POLL = 10.seconds
        val SYNC_BURST_TIMEOUT = 2.minutes
    }
}
