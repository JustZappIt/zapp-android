// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.p2p

/**
 * Local cache of `orderId → recipient UPI` populated at placement time.
 *
 * The chain stores `encUpi` encrypted to the **merchant's** public key — that's the field the
 * merchant decrypts to know where to send the INR. The user cannot decrypt their own `encUpi`,
 * so the only way to surface "you paid VPA X" in the history list is to cache it locally when
 * the order is placed. Mirrors `getPaymentAddressFromOrderDetails` in the official user-app-client.
 *
 * `encMerchantUpi` (encrypted to the user's relay pubkey at `completeOrder`) IS user-decryptable,
 * but the merchant pool we hit on mainnet leaves it empty in practice, so we can't rely on it.
 */
interface OrderRecipientUpiCache {
    suspend fun put(orderId: String, recipientUpi: String)

    suspend fun get(orderId: String): String?
}

class InMemoryOrderRecipientUpiCache : OrderRecipientUpiCache {
    private val map = mutableMapOf<String, String>()

    override suspend fun put(orderId: String, recipientUpi: String) {
        map[orderId] = recipientUpi
    }

    override suspend fun get(orderId: String): String? = map[orderId]
}
