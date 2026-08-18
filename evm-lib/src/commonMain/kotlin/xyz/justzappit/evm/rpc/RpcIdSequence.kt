// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.rpc

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class RpcIdSequence {
    private val mutex = Mutex()
    private var nextId = 1L

    suspend fun next(): Long = mutex.withLock { nextId++ }
}
