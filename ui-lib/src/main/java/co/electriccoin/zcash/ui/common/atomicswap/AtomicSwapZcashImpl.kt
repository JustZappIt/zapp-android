// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.atomicswap

import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.datasource.ZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import xyz.justzappit.atomicswap.AtomicSwap
import xyz.justzappit.offramp.atomicswap.AtomicSwapBlock
import xyz.justzappit.offramp.atomicswap.AtomicSwapBlockedException
import xyz.justzappit.offramp.atomicswap.AtomicSwapZcash
import xyz.justzappit.offramp.atomicswap.PreparedDeposit
import xyz.justzappit.offramp.atomicswap.SwapShare
import xyz.justzappit.offramp.atomicswap.SwapZcash
import xyz.justzappit.offramp.atomicswap.ZcashTransaction
import xyz.justzappit.offramp.atomicswap.ZcashTransactionStatus

class AtomicSwapZcashImpl(
    private val synchronizerProvider: SynchronizerProvider,
    private val accountDataSource: AccountDataSource,
    private val proposalDataSource: ProposalDataSource,
    private val zashiSpendingKeyDataSource: ZashiSpendingKeyDataSource,
    private val transactions: SwapZcashTransactions,
    private val jointAccounts: JointAccounts,
    private val keys: SwapKeyring,
) : AtomicSwapZcash,
    SwapZcash by transactions {
    override suspend fun prepareDeposit(
        address: String,
        zatoshi: Long,
        maxTotalZat: Long?,
    ): PreparedDeposit {
        val proposal =
            synchronizerProvider
                .getSynchronizer()
                .proposeTransfer(accountDataSource.getZashiAccount().sdkAccount, address, Zatoshi(zatoshi))
        val total = zatoshi + proposal.totalFeeRequired().value
        val overCap = maxTotalZat != null && total > maxTotalZat
        if (proposal.transactionCount() != 1 || overCap) {
            throw AtomicSwapBlockedException(
                AtomicSwapBlock.DEPOSIT_UNPAYABLE,
                "the deposit takes ${proposal.transactionCount()} transactions and $total zatoshi",
            )
        }
        val usk = zashiSpendingKeyDataSource.getZashiSpendingKey()
        return PreparedDeposit { proposalDataSource.createTransactions(proposal, usk).single().kept() }
    }

    override suspend fun findDeposit(address: String): ZcashTransaction? {
        val synchronizer = synchronizerProvider.getSynchronizer()
        return synchronizer
            .getTransactions(walletAccount())
            .first()
            .filter { it.isSentTransaction && synchronizer.status(it) != ZcashTransactionStatus.Expired }
            .firstOrNull { sent -> synchronizer.getRecipients(sent).toList().any { it.addressValue == address } }
            ?.kept()
    }

    override suspend fun depositStatus(deposit: ZcashTransaction) = transactions.status(walletAccount(), deposit)

    override suspend fun prepareSweep(
        index: Int,
        makerShare: SwapShare,
        makerSecret: ByteArray,
        birthday: Long,
    ): ZcashTransaction? {
        val account = jointAccounts.import(index, makerShare, birthday)
        return jointAccounts
            .sweep(account) { pczt, intent ->
                keys.withKey(index) { AtomicSwap.signRefund(it, makerShare.bytes, makerSecret, pczt, intent) }
            }?.transaction
    }

    override suspend fun sweepStatus(
        index: Int,
        makerShare: SwapShare,
        sweep: ZcashTransaction,
    ): ZcashTransactionStatus =
        jointAccounts.find(index, makerShare)?.let { transactions.status(it.accountUuid, sweep) }
            ?: ZcashTransactionStatus.Unknown

    override suspend fun forgetDepositAccount(
        index: Int,
        makerShare: SwapShare
    ) = jointAccounts.forget(index, makerShare)

    private suspend fun walletAccount(): AccountUuid = accountDataSource.getZashiAccount().sdkAccount.accountUuid
}
