// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.provider

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** Serializes creation and reconciliation of one card's funding transaction within this process. */
class GiftFundingOperationLock {
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun <T> withLock(
        cardId: String,
        block: suspend () -> T,
    ): T = locks.getOrPut(cardId) { Mutex() }.withLock { block() }
}
