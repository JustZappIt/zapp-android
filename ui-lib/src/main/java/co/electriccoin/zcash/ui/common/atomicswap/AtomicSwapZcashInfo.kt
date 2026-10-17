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
    /** The network fee of paying [offer]'s deposit from the wallet now, or null unless one transaction can. */
    suspend fun depositFee(offer: AtomicSwapOffer): Long? =
        try {
            val proposal =
                synchronizerProvider
                    .getSynchronizer()
                    .proposeTransfer(
                        accountDataSource.getZashiAccount().sdkAccount,
                        keys.depositAddress(offer.index, offer.quote.makerShare),
                        Zatoshi(offer.quote.depositZat),
                    )
            proposal.totalFeeRequired().value.takeIf { proposal.transactionCount() == 1 }
        } catch (e: SdkException) {
            Twig.info { "Atomic swap: no deposit fee estimate, ${e.message}" }
            null
        }

    /** How many blocks have mined the wallet's [txId] so far, or null while the chain's height is unknown. */
    suspend fun confirmations(txId: ZcashTxId): Int? {
        if (synchronizerProvider.getSynchronizer().networkHeight.value == null) return null
        val account = accountDataSource.getZashiAccount().sdkAccount.accountUuid
        val status = transactions.find(account, txId)
        return ((status as? ZcashTransactionStatus.Mined)?.confirmations ?: 0).toInt()
    }
}
