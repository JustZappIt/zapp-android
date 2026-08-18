// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.apple

import xyz.justzappit.offramp.p2p.OrderRecipientUpiCache
import xyz.justzappit.offramp.p2p.RelayIdentity
import xyz.justzappit.offramp.p2p.RelayIdentityStore

/** Small encrypted-storage surface implemented by iOS. Values are scoped to the selected wallet. */
data class AppleStorageValue(
    val value: String?,
)

interface AppleOfframpStorage {
    @Throws(Exception::class)
    fun relayPrivateKey(): AppleStorageValue

    @Throws(Exception::class)
    fun relayPublicKey(): AppleStorageValue

    @Throws(Exception::class)
    fun storeRelay(privateKeyHex: String, publicKeyHex: String)

    @Throws(Exception::class)
    fun paymentAddress(orderId: String): AppleStorageValue

    @Throws(Exception::class)
    fun storePaymentAddress(orderId: String, paymentAddress: String)

    @Throws(Exception::class)
    fun checkpointJson(): AppleStorageValue

    @Throws(Exception::class)
    fun storeCheckpointJson(value: String)

    @Throws(Exception::class)
    fun clearCheckpoint()

    @Throws(Exception::class)
    fun topUpCheckpointJson(): AppleStorageValue

    @Throws(Exception::class)
    fun storeTopUpCheckpointJson(value: String)

    @Throws(Exception::class)
    fun clearTopUpCheckpoint()

    @Throws(Exception::class)
    fun refundCheckpointJson(): AppleStorageValue

    @Throws(Exception::class)
    fun storeRefundCheckpointJson(value: String)

    @Throws(Exception::class)
    fun clearRefundCheckpoint()
}

internal class AppleRelayIdentityStore(
    private val storage: AppleOfframpStorage,
) : RelayIdentityStore {
    override suspend fun get(): RelayIdentity? {
        val privateKey = storage.relayPrivateKey().value ?: return null
        val publicKey = storage.relayPublicKey().value ?: return null
        return RelayIdentity(privateKeyHex = privateKey, publicKeyHex = publicKey)
    }

    override suspend fun set(identity: RelayIdentity) =
        storage.storeRelay(identity.privateKeyHex, identity.publicKeyHex)
}

internal class AppleOrderRecipientCache(
    private val storage: AppleOfframpStorage,
) : OrderRecipientUpiCache {
    override suspend fun put(orderId: String, recipientUpi: String) =
        storage.storePaymentAddress(orderId, recipientUpi)

    override suspend fun get(orderId: String): String? = storage.paymentAddress(orderId).value
}
