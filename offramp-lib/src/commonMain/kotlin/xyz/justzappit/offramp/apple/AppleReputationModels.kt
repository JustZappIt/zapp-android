// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.apple

/**
 * One address's standing on the exchange, flattened for Swift: amounts as 6-decimal micro strings,
 * platforms as a list rather than the maps and enum keys the shared model uses.
 *
 * Every figure here is the chain's own. Nothing on the Swift side may derive a limit from [points].
 *
 * Two per-order limits meet here: the Diamond's, set by reputation, and Zapp's integrator's, set
 * by the selfie check. A buy is routed by whichever carries the amount, so [shownLimitMicros] is
 * the higher of the two and [canBuy] is whether it is positive — the same two reads
 * `OnrampRouteReader` makes, so the gate and the amount screen cannot disagree. Blacklist
 * precedence stays on the Swift side, where it already was.
 */
data class AppleReputationSummary(
    val currencyCode: String,
    val points: String,
    val isBlacklisted: Boolean,
    val canBuy: Boolean,
    val isAtCeiling: Boolean,
    /** The Diamond's own per-order limit, from reputation alone. */
    val buyLimitMicros: String,
    val maxBuyLimitMicros: String,
    /** The higher of the Diamond's limit and the integrator's; what the screen shows. */
    val shownLimitMicros: String,
    /** True when the integrator's limit is the one shown, so the caption can name it. */
    val isLimitFromCheckout: Boolean,
    /** False on a network with no integrator, where nothing about the selfie check is mentioned. */
    val isSelfieAvailable: Boolean,
    /**
     * The standing [shownLimitMicros] was computed from, so the screen that lists the selfie row
     * reads it once, here, rather than again beside the summary. Null where no integrator is
     * deployed, and after a failed confirming read on a Reclaim `Done`.
     */
    val liveness: AppleLivenessStanding?,
    val platforms: List<AppleReputationPlatform>,
)

data class AppleReputationPlatform(
    /** `SocialPlatform.name` — the routing key, and what the return link carries. */
    val id: String,
    /** `SocialPlatform.onChainName` — the brand's own spelling. Never translated. */
    val name: String,
    val awardPoints: String,
    val isVerified: Boolean,
    val requiresMatureAccount: Boolean,
    /** `ReputationSummary.limitGainFor`, micros. Null means "say nothing", never "zero". */
    val limitGainMicros: String?,
)

/**
 * The verification run, as Swift sees it.
 *
 * [Ready] carries only the request URL. The shared status also carries two Play links, which are
 * wrong on iOS: the App Store fallback belongs to the Swift side, next to the store it names.
 */
sealed class AppleReclaimStatus {
    data object Preparing : AppleReclaimStatus()

    data class Ready(
        val requestUrl: String
    ) : AppleReclaimStatus()

    data object Verifying : AppleReclaimStatus()

    data object Submitting : AppleReclaimStatus()

    data class Done(
        val summary: AppleReputationSummary
    ) : AppleReclaimStatus()

    /** `ReclaimFailure.name` — Swift maps it to a sentence, one per case. */
    data class Failed(
        val reason: String
    ) : AppleReclaimStatus()
}
