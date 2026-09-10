// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.apple

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import xyz.justzappit.offramp.p2p.CurrencyCode
import xyz.justzappit.offramp.reputation.SocialPlatform

/**
 * Resolves the two routing keys Swift sends as strings, then admits one run at a time.
 *
 * [lock] is held for exactly as long as the collection lives, so cancelling an abandoned run
 * frees the next one. A second live session is what the guard exists to prevent: it leaves the
 * user verifying against the link they opened while the poller watches a session nobody ever
 * touched — ten minutes of waiting ending in a session expired on a verification that in fact
 * succeeded.
 */
internal fun singleRunFlow(
    lock: Mutex,
    platformId: String,
    currencyCode: String,
    block: suspend FlowCollector<AppleReclaimStatus>.(SocialPlatform, CurrencyCode) -> Unit,
): Flow<AppleReclaimStatus> =
    flow {
        val platform = SocialPlatform.entries.firstOrNull { it.name == platformId }
        val currency = CurrencyCode.fromCodeOrNull(currencyCode)
        if (platform == null || currency == null) {
            emit(AppleReclaimStatus.Failed(AppleReputationClient.UNKNOWN_REASON))
            return@flow
        }
        if (!lock.tryLock()) {
            emit(AppleReclaimStatus.Failed(AppleReputationClient.BUSY_REASON))
            return@flow
        }
        try {
            block(platform, currency)
        } finally {
            lock.unlock()
        }
    }
