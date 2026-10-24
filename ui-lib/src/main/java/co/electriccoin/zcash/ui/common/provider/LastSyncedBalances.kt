// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.provider

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.AccountBalance
import cash.z.ecc.android.sdk.model.AccountUuid
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flow

/**
 * [balances] as they were when [status] last read SYNCED, held while the wallet syncs again; before
 * its first sync, as they come. Read the split across pools from these, never from the live ones.
 *
 * While a sync still has blocks to scan below the height an account was imported at, the SDK's
 * summary collapses every account's balance into spendable Orchard, every other pool empty
 * (backend-lib `slipstream/summary.rs`). Each conversion imports its joint account, so for the
 * seconds its blocks take to scan, Ironwood funds read as Orchard: the breakdown showed them there
 * and the wallet asked to migrate them.
 *
 * The engine publishes each tick's balances a moment before its status, so a pair counts only once
 * it has held for [SETTLE_MILLIS].
 */
@OptIn(FlowPreview::class)
fun lastSyncedBalances(
    balances: Flow<Map<AccountUuid, AccountBalance>?>,
    status: Flow<Synchronizer.Status>,
): Flow<Map<AccountUuid, AccountBalance>?> =
    flow {
        var synced: Map<AccountUuid, AccountBalance>? = null
        combine(balances, status, ::Pair)
            .debounce(SETTLE_MILLIS)
            .collect { (current, now) ->
                if (now == Synchronizer.Status.SYNCED) synced = current
                emit(synced ?: current)
            }
    }

private const val SETTLE_MILLIS = 300L
