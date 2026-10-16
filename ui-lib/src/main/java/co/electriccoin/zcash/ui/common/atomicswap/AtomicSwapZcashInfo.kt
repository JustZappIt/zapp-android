// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.atomicswap

import cash.z.ecc.android.sdk.exception.SdkException
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import xyz.justzappit.offramp.atomicswap.AtomicSwapKeys
import xyz.justzappit.offramp.atomicswap.AtomicSwapOffer
import xyz.justzappit.offramp.atomicswap.ZcashTransactionStatus
import xyz.justzappit.offramp.atomicswap.ZcashTxId

/** What the screens show about a forward swap's Zcash side. */
class AtomicSwapZcashInfo(
    private val synchronizerProvider: SynchronizerProvider,
    private val accountDataSource: AccountDataSource,
    private val transactions: SwapZcashTransactions,
    private val keys: AtomicSwapKeys,
) {
    /** What paying [offer]'s deposit from the wallet now costs in network fee, or why one transaction can't. */
    suspend fun depositFee(offer: AtomicSwapOffer): DepositFee =
        try {
            val proposal =
                synchronizerProvider
                    .getSynchronizer()
                    .proposeTransfer(
                        accountDataSource.getZashiAccount().sdkAccount,
                        keys.depositAddress(offer.index, offer.quote.makerShare),
                        Zatoshi(offer.quote.depositZat),
                    )
            val count = proposal.transactionCount()
            if (count == 1) {
                DepositFee.Payable(proposal.totalFeeRequired().value)
            } else {
                Twig.info { "Atomic swap: the deposit takes $count transactions" }
                DepositFee.Split
            }
        } catch (e: SdkException) {
            Twig.info { "Atomic swap: no deposit fee estimate, ${e.message}" }
            e.shortfall()?.let { DepositFee.Short(it) } ?: DepositFee.Unready
        }

    /** How many blocks have mined the wallet's [txId] so far, or null while the chain's height is unknown. */
    suspend fun confirmations(txId: ZcashTxId): Int? {
        if (synchronizerProvider.getSynchronizer().networkHeight.value == null) return null
        val account = accountDataSource.getZashiAccount().sdkAccount.accountUuid
        val status = transactions.find(account, txId)
        return ((status as? ZcashTransactionStatus.Mined)?.confirmations ?: 0).toInt()
    }
}

/** Paying a forward swap's deposit from the wallet now: its network fee, or why the wallet can't. */
sealed interface DepositFee {
    data class Payable(
        val zat: Long
    ) : DepositFee

    /** The spendable balance is [zat] short of the deposit and its fee. */
    data class Short(
        val zat: Long
    ) : DepositFee

    /** It takes more than one transaction. */
    data object Split : DepositFee

    /** Not synced, or the wallet can't propose a payment for some other reason. */
    data object Unready : DepositFee
}

// librustzcash names both amounts when the balance can't cover a payment and its fee.
private val INSUFFICIENT_BALANCE = Regex("""Insufficient balance \(have (\d+), need (\d+)""")

private fun SdkException.shortfall(): Long? =
    message?.let(INSUFFICIENT_BALANCE::find)?.destructured?.let { (have, need) ->
        (need.toLong() - have.toLong()).takeIf { it > 0 }
    }
