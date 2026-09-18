// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.liveness

/**
 * The liveness verifier this build talks to, and the tenant key that opens a session and redeems
 * its result.
 *
 * The key ships in the APK on the same reasoning as [xyz.justzappit.offramp.reclaim.ReclaimAppCredentials]:
 * an extracted key can open sessions and redeem codes, but it cannot mint an attestation — the
 * service signs those only after a face passes liveness and dedup — and the attestation binds to
 * the wallet that submits it. What it can do is burn our quota.
 */
data class LivenessConfig(
    val apiUrl: String,
    val apiKey: String,
    val tenant: String,
    /** The build's kill switch: false hides the check and refuses to start one, credentials or not. */
    val enabled: Boolean = true,
) {
    val isConfigured: Boolean
        get() = enabled && apiUrl.isNotBlank() && apiKey.isNotBlank() && tenant.isNotBlank()
}
