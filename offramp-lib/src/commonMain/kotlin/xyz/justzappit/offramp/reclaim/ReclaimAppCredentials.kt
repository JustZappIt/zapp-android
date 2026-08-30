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
    /**
     * ☠ Checked, never thrown on. This is registered as a lazy Koin `single`, so a `require` here
     * would surface as a crash inside composition — the first `koinViewModel<IncreaseReputationVM>()`
     * — for a developer whose `local.properties` holds a truncated paste. [ReclaimVerificationDriver]
     * already branches on this to emit [ReclaimFailure.NotConfigured], which says "verification is
     * unavailable" instead. A build without working credentials is a build that cannot verify, not
     * a build that cannot run.
     *
     * Note what this does *not* police: the appId travels as the exact string Reclaim was
     * registered with, because the TEE nonce hashes that string rather than the address, so
     * re-casing it silently changes the nonce. [Address.parseOrNull] is case-insensitive and
     * cannot catch that.
     */
    val isConfigured: Boolean
        get() = appSecret.isNotBlank() && Address.parseOrNull(appId) != null
}
