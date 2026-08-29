// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.reclaim

import xyz.justzappit.evm.types.Address

/**
 * Our Reclaim application's identity. The `appId` **is** an Ethereum address and the `appSecret`
 * **is** its private key, which is why every session is signed rather than merely labelled.
 *
 * Both ship in the APK, deliberately. An extracted secret cannot forge a proof — Reclaim's
 * attestors sign those — and cannot farm reputation, because the proof still binds to the
 * `msg.sender` that submits it. It can burn our Reclaim quota and impersonate our app, and it
 * rotates only when users update. p2p.me ships the same secret client-side in their own web app.
 * Watch session volume; that is the only signal an extraction gives us.
 */
data class ReclaimAppCredentials(
    val appId: String,
    val appSecret: String,
) {
    val isConfigured: Boolean
        get() = appSecret.isNotBlank() && Address.parseOrNull(appId) != null

    init {
        // The appId travels as the exact string Reclaim was registered with: the TEE nonce hashes
        // that string, not the address, so re-casing it silently changes the nonce.
        require(appId.isBlank() || Address.parseOrNull(appId) != null) {
            "RECLAIM_APP_ID must be an EVM address, the signer's own"
        }
    }
}
