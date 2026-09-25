// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.apple

/**
 * One address's standing on the exchange, flattened for Swift: amounts as 6-decimal micro strings,
 * platforms as a list rather than the maps and enum keys the shared model uses.
 *
 * Every figure here is the chain's own. Nothing on the Swift side may derive a limit from [points].
 */
data class AppleReputationSummary(
    val currencyCode: String,
    val points: String,
    val isBlacklisted: Boolean,
    val canBuy: Boolean,
    val isAtCeiling: Boolean,
    val buyLimitMicros: String,
    val maxBuyLimitMicros: String,
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
