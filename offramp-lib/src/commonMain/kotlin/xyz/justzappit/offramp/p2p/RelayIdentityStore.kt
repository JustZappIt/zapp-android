// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.p2p

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Persistent home for the user's ECDH relay identity. SEPARATE from the EOA / smart-account
 * keypair that signs transactions: the relay key seals the user's UPI to merchants and decrypts
 * `encMerchantUpi` on completion. Losing it makes every past order's merchant UPI permanently
 * undecryptable — see §7 of the offramp findings doc.
 */
interface RelayIdentityStore {
    suspend fun get(): RelayIdentity?

    suspend fun set(identity: RelayIdentity)
}

suspend fun RelayIdentityStore.getOrCreate(generate: () -> RelayIdentity = RelayIdentities::generate): RelayIdentity {
    get()?.let { return it }
    return RelayIdentityStoreLock.mutex.withLock {
        get() ?: generate().also { set(it) }
    }
}

private object RelayIdentityStoreLock {
    val mutex = Mutex()
}

class InMemoryRelayIdentityStore(
    initial: RelayIdentity? = null
) : RelayIdentityStore {
    private var identity: RelayIdentity? = initial

    override suspend fun get(): RelayIdentity? = identity

    override suspend fun set(identity: RelayIdentity) {
        this.identity = identity
    }
}
