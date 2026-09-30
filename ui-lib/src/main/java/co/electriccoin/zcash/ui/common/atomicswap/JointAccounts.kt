// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.atomicswap

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.exception.TransactionEncoderException
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.AccountImportSetup
import cash.z.ecc.android.sdk.model.AccountPurpose
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.Pczt
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.TransactionOverview
import cash.z.ecc.android.sdk.model.UnifiedFullViewingKey
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.Zip32AccountIndex
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.datasource.ATOMIC_SWAP_KEYSOURCE
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.isInsufficientFunds
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import xyz.justzappit.atomicswap.AtomicSwap
import xyz.justzappit.offramp.atomicswap.SwapShare
import xyz.justzappit.offramp.atomicswap.ZcashTransaction
import xyz.justzappit.offramp.atomicswap.ZcashTransactionStatus

/** A sweep of a joint account home, created and kept but not sent. */
class JointSweep(
    val transaction: ZcashTransaction,
    val receivedZat: Long,
    val feeZat: Long,
)

/** What a joint account received with enough confirmations, and the most sweeping it home would cost. */
class JointSweepEstimate(
    val availableZat: Long,
    val feeZat: Long,
)

/** Each swap's account of its key and the maker's share, watched, swept home, then forgotten. */
class JointAccounts(
    private val synchronizers: SynchronizerProvider,
    private val accounts: AccountDataSource,
    private val transactions: SwapZcashTransactions,
    private val keys: SwapKeyring,
) {
    suspend fun find(
        index: Int,
        makerShare: SwapShare
    ): Account? = synchronizers.getSynchronizer().watching(viewingKey(index, makerShare))

    /** The joint account, imported from [birthday] unless the wallet watches it: it's known by its key alone. */
    suspend fun import(
        index: Int,
        makerShare: SwapShare,
        birthday: Long,
    ): Account {
        val synchronizer = synchronizers.getSynchronizer()
        val ufvk = viewingKey(index, makerShare)
        return synchronizer.watching(ufvk) ?: synchronizer.importAccountByUfvk(
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
    }

    /** [account]'s whole balance home, the sweep made before or one [sign] signs; null until all is spendable. */
    suspend fun sweep(
        account: Account,
        sign: suspend (redactedPczt: ByteArray) -> ByteArray,
    ): JointSweep? {
        val synchronizer = transactions.synced()
        return earlierSweep(synchronizer, account)
            ?: synchronizer.settled(account)?.let { build(synchronizer, account, it, sign) }
    }

    /**
     * What [account] received in transactions with [confirmations] or more, against the tip the wallet synced to, and
     * the most a sweep of those notes pays: an action a note and one for its output, the grace at least.
     */
    suspend fun estimate(
        account: Account,
        confirmations: Int,
    ): JointSweepEstimate {
        val synchronizer = transactions.synced()
        val received =
            synchronizer
                .getTransactions(account.accountUuid)
                .first()
                .filter { !it.isSentTransaction && synchronizer.confirmations(it) >= confirmations }
        val notes = received.sumOf { it.receivedNoteCount }
        return JointSweepEstimate(
            availableZat = received.sumOf { it.netValue.value },
            feeZat = ZIP317_MARGINAL_FEE * maxOf(ZIP317_GRACE_ACTIONS, notes + 1),
        )
    }

    /** Stops watching the joint account, if the wallet does. A failure leaves only scanning to pay for. */
    suspend fun forget(
        index: Int,
        makerShare: SwapShare
    ) {
        val account = find(index, makerShare) ?: return
        try {
            synchronizers.getSynchronizer().deleteAccount(account.accountUuid)
        } catch (e: CancellationException) {
            throw e
        } catch (ignored: Exception) {
            Twig.warn(ignored) { "Atomic swap: a joint account is still watched" }
        }
    }

    private suspend fun viewingKey(
        index: Int,
        makerShare: SwapShare
    ): String = keys.withKey(index) { AtomicSwap.depositAccount(it, makerShare.bytes).ufvk }

    // A joint account never sends anything but its sweep.
    private suspend fun earlierSweep(
        synchronizer: Synchronizer,
        account: Account
    ): JointSweep? =
        synchronizer
            .getTransactions(account.accountUuid)
            .first()
            .firstOrNull { it.isSentTransaction && synchronizer.status(it) != ZcashTransactionStatus.Expired }
            ?.let { sent ->
                val fee = checkNotNull(sent.feePaid).value
                JointSweep(sent.kept(), sent.netValue.value - fee, fee)
            }

    private suspend fun build(
        synchronizer: Synchronizer,
        account: Account,
        balance: Long,
        sign: suspend (ByteArray) -> ByteArray,
    ): JointSweep {
        val sweep = proposal(synchronizer, account, balance)
        val pczt = synchronizer.createPcztFromProposal(account.accountUuid, sweep.proposal)
        val withProofs = synchronizer.addProofsToPczt(pczt.clonePczt())
        val redacted = synchronizer.redactPcztForSigner(pczt.clonePczt())
        val signed = sign(redacted.toByteArray())
        val created = synchronizer.broadcaster.createTransactionFromPczt(withProofs, Pczt(signed)).single()
        return JointSweep(created.kept(), balance - sweep.feeZat, sweep.feeZat)
    }

    private suspend fun proposal(
        synchronizer: Synchronizer,
        account: Account,
        balance: Long,
    ): SweepProposal<Proposal> {
        val zashi = accounts.getZashiAccount()
        val home = zashi.unified.address.address
        return proposeSweep(balance) { amount ->
            try {
                val proposal = synchronizer.proposeTransfer(account, home, Zatoshi(amount))
                SweepProposal(proposal, proposal.totalFeeRequired().value, proposal.transactionCount())
            } catch (_: TransactionEncoderException.InsufficientFundsException) {
                null
            } catch (e: TransactionEncoderException.ProposalFromParametersException) {
                if (e.isInsufficientFunds()) null else throw e
            }
        }
    }
}

/** What a sweep needs of a proposal. */
internal class SweepProposal<P>(
    val proposal: P,
    val feeZat: Long,
    val transactions: Int,
)

/** All of [balance] with no change: ZIP 317 charges per note, so the fee grows a note at a time until it fits. */
internal suspend fun <P> proposeSweep(
    balance: Long,
    propose: suspend (amountZat: Long) -> SweepProposal<P>?,
): SweepProposal<P> {
    var fee = ZIP317_MIN_FEE_ZAT
    repeat(MAX_SWEEP_PROPOSALS) {
        check(balance > fee) { "the balance doesn't cover a sweep's fee" }
        val proposal = propose(balance - fee)
        when {
            proposal == null -> fee += ZIP317_MARGINAL_FEE
            proposal.feeZat == fee && proposal.transactions == 1 -> return proposal
            else -> fee = proposal.feeZat
        }
    }
    error("no sweep of the balance fits one transaction")
}

private suspend fun Synchronizer.watching(ufvk: String): Account? =
    getAccounts().firstOrNull { it.keySource == ATOMIC_SWAP_KEYSOURCE && it.ufvk == ufvk }

private fun Synchronizer.confirmations(overview: TransactionOverview): Long =
    (status(overview) as? ZcashTransactionStatus.Mined)?.confirmations ?: 0

private fun Synchronizer.available(account: Account): Long {
    val balance = walletBalances.value?.get(account.accountUuid) ?: return 0
    return (balance.orchard.available + balance.ironwood.available).value
}

// Every note spendable, so the sweep leaves none behind.
private fun Synchronizer.settled(account: Account): Long? {
    val balance = walletBalances.value?.get(account.accountUuid) ?: return null
    val pending = balance.orchard.valuePending + balance.ironwood.valuePending
    return available(account).takeIf { it > 0 && pending.value == 0L }
}

private const val ZIP317_MARGINAL_FEE = 5_000L
private const val ZIP317_GRACE_ACTIONS = 2

/** ZIP 317's fee for the fewest actions any transaction pays for: the least a deposit or a sweep costs. */
internal const val ZIP317_MIN_FEE_ZAT = ZIP317_MARGINAL_FEE * ZIP317_GRACE_ACTIONS
private const val MAX_SWEEP_PROPOSALS = 64
