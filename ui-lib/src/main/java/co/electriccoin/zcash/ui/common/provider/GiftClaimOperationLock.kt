// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.provider

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

class GiftClaimOperationLock {
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun <T> withLock(
        cardAddress: String,
        block: suspend () -> T,
    ): T = locks.getOrPut(cardAddress) { Mutex() }.withLock { block() }
}
