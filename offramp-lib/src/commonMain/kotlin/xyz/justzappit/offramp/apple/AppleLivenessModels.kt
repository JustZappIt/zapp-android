// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.apple

/**
 * One wallet's standing on Zapp's liveness integrator, flattened for Swift: amounts as 6-decimal
 * micro strings. Every figure is the chain's own; nothing on the Swift side derives a limit.
 */
data class AppleLivenessStanding(
    val isVerified: Boolean,
    /** What this wallet may buy per order through the integrator; 0 until verified. */
    val limitMicros: String,
    /** What verifying is worth to a wallet that has not yet. */
    val tierCapMicros: String,
)

/**
 * The selfie check, as Swift sees it. Same stages as [AppleReclaimStatus]: the row's run body
 * is shared, and only the words differ.
 */
sealed class AppleLivenessStatus {
    data object Preparing : AppleLivenessStatus()

    /** A widget session is open; the user has [expiresInSeconds] to finish it in the browser. */
    data class Ready(
        val widgetUrl: String,
        val expiresInSeconds: Int,
    ) : AppleLivenessStatus()

    data object Verifying : AppleLivenessStatus()

    data object Submitting : AppleLivenessStatus()

    data class Done(
        val standing: AppleLivenessStanding,
    ) : AppleLivenessStatus()

    /** `LivenessFailure.name`, or the facade's own busy / unknown reasons — Swift maps each to a sentence. */
    data class Failed(
        val reason: String,
    ) : AppleLivenessStatus()
}
