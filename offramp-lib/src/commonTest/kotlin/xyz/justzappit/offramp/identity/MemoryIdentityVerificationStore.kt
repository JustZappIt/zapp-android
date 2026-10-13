// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 The Zapp Contributors

package xyz.justzappit.offramp.identity

/** Round-trips the serialized record so recovery tests cannot depend on retained object references. */
internal class MemoryIdentityVerificationStore : IdentityVerificationStore {
    var failTransactionWrites = false
    val records = mutableMapOf<String, String>()

    override suspend fun get(key: String): PendingIdentityVerification? =
        records[key]?.let {
            kotlinx.serialization.json.Json
                .decodeFromString<PendingIdentityVerification>(it)
        }

    override suspend fun set(key: String, pending: PendingIdentityVerification?) {
        check(!failTransactionWrites || pending?.transactionHash == null) { "disk unavailable" }
        if (pending == null) {
            records.remove(key)
        } else {
            records[key] =
                kotlinx.serialization.json.Json
                    .encodeToString(PendingIdentityVerification.serializer(), pending)
        }
    }
}
