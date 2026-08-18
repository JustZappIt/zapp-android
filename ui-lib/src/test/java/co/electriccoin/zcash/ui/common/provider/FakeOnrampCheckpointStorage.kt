// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.provider

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import xyz.justzappit.offramp.onramp.OnrampCheckpoint

/** In-memory [OnrampCheckpointStorageProvider], optionally failing reads the way a corrupt blob does. */
internal class FakeOnrampCheckpointStorage(
    initial: OnrampCheckpoint? = null,
    private val readFailure: Throwable? = null,
) : OnrampCheckpointStorageProvider {
    private val state = MutableStateFlow(initial)

    override suspend fun get(): OnrampCheckpoint? = readFailure?.let { throw it } ?: state.value

    override suspend fun store(checkpoint: OnrampCheckpoint) {
        state.value = checkpoint
    }

    override suspend fun clear() {
        state.value = null
    }

    override fun observe(): Flow<OnrampCheckpoint?> = state
}
