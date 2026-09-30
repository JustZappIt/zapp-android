// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.atomicswap

import cash.z.ecc.android.sdk.model.Account
import xyz.justzappit.atomicswap.ReverseAtomicSwap
import xyz.justzappit.offramp.atomicswap.JointAccountId
import xyz.justzappit.offramp.atomicswap.ReverseReceiveEstimate
import xyz.justzappit.offramp.atomicswap.ReverseReceiveTransaction
import xyz.justzappit.offramp.atomicswap.ReverseSwapRecord
import xyz.justzappit.offramp.atomicswap.ReverseSwapZcash
import xyz.justzappit.offramp.atomicswap.SwapZcash
import xyz.justzappit.offramp.atomicswap.ZcashTransactionStatus

/** The reverse swap's Zcash side. Its joint account is found by its key, and imported again if the wallet lost it. */
class ReverseSwapZcashImpl(
    private val transactions: SwapZcashTransactions,
    private val jointAccounts: JointAccounts,
    private val keys: SwapKeyring,
) : ReverseSwapZcash,
    SwapZcash by transactions {
    override suspend fun importAccount(record: ReverseSwapRecord): JointAccountId =
        JointAccountId.of(account(record).accountUuid.value)

    override suspend fun spendable(record: ReverseSwapRecord): Long = jointAccounts.spendable(account(record))

    override suspend fun estimateReceive(record: ReverseSwapRecord): ReverseReceiveEstimate =
        jointAccounts.estimate(account(record)).let { ReverseReceiveEstimate(it.availableZat, it.feeZat) }

    override suspend fun prepareReceive(
        record: ReverseSwapRecord,
        makerSecret: ByteArray
    ): ReverseReceiveTransaction? =
        jointAccounts
            .sweep(account(record)) { redacted ->
                keys.withKey(record.index) {
                    ReverseAtomicSwap.signReceive(it, record.quote.terms.makerShare.bytes, makerSecret, redacted)
                }
            }?.let { sweep ->
                val transaction = sweep.transaction
                require(sweep.receivedZat > 0 && sweep.feeZat > 0 && transaction.expiryHeight > 0) { "an empty sweep" }
                ReverseReceiveTransaction(
                    transaction.txId,
                    transaction.raw,
                    transaction.expiryHeight,
                    sweep.receivedZat,
                    sweep.feeZat
                )
            }

    override suspend fun receiveStatus(
        record: ReverseSwapRecord,
        receive: ReverseReceiveTransaction
    ): ZcashTransactionStatus = transactions.status(account(record).accountUuid, receive.transaction)

    override suspend fun forget(record: ReverseSwapRecord) {
        if (record.account != null) jointAccounts.forget(record.index, record.quote.terms.makerShare)
    }

    private suspend fun account(record: ReverseSwapRecord): Account =
        jointAccounts.import(record.index, record.quote.terms.makerShare, record.birthday)
}
