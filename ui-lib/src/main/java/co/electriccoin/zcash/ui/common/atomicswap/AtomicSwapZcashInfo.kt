// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.atomicswap

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import kotlinx.coroutines.flow.first

/** What the screens show about a swap's Zcash side. */
class AtomicSwapZcashInfo(
    private val synchronizerProvider: SynchronizerProvider,
    private val accountDataSource: AccountDataSource,
) {
    /** The network fee of paying [zatoshi] to [address] from the wallet now; throws when it can't be paid. */
    suspend fun depositFee(
        address: String,
        zatoshi: Long
    ): Long =
        synchronizerProvider
            .getSynchronizer()
            .proposeTransfer(accountDataSource.getZashiAccount().sdkAccount, address, Zatoshi(zatoshi))
            .totalFeeRequired()
            .value

    /** How many blocks have mined [txId] so far, or null while the chain's height is unknown. */
    suspend fun confirmations(txId: String): Int? {
        val synchronizer = synchronizerProvider.getSynchronizer()
        val minedHeight =
            synchronizer
                .getTransactions(accountDataSource.getZashiAccount().sdkAccount.accountUuid)
                .first()
                .firstOrNull { it.txId.txIdString() == txId }
                ?.minedHeight
        return synchronizer.networkHeight.value?.let { tip ->
            minedHeight?.let { (tip.value - it.value + 1).toInt().coerceAtLeast(0) } ?: 0
        }
    }
}
