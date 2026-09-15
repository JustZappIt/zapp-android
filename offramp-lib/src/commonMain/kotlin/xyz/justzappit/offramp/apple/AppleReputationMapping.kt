// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.apple

import xyz.justzappit.offramp.liveness.LivenessStanding
import xyz.justzappit.offramp.p2p.Usdc6
import xyz.justzappit.offramp.reclaim.ReclaimStatus
import xyz.justzappit.offramp.reputation.ReputationSummary
import xyz.justzappit.offramp.reputation.SocialPlatform

internal fun ReclaimStatus.toApple(
    standing: LivenessStanding? = null,
    isSelfieAvailable: Boolean = false,
): AppleReclaimStatus =
    when (this) {
        ReclaimStatus.Preparing -> AppleReclaimStatus.Preparing

        // Only the request URL crosses. The other two links are Play's, and iOS's own fallback
        // belongs on the Swift side, next to the store it names.
        is ReclaimStatus.Ready -> AppleReclaimStatus.Ready(requestUrl)

        ReclaimStatus.Verifying -> AppleReclaimStatus.Verifying

        ReclaimStatus.Submitting -> AppleReclaimStatus.Submitting

        is ReclaimStatus.Done -> AppleReclaimStatus.Done(summary.toApple(standing, isSelfieAvailable))

        is ReclaimStatus.Failed -> AppleReclaimStatus.Failed(reason.name)
    }

/** [standing] is the integrator's answer for the same wallet, or null where none is deployed. */
internal fun ReputationSummary.toApple(
    standing: LivenessStanding? = null,
    isSelfieAvailable: Boolean = false,
): AppleReputationSummary {
    // Reputation first, the selfie as fallback — the route's own policy, so the number shown is
    // the number a buy is sized by. A tie is the Diamond's.
    val checkout = standing?.limit ?: Usdc6.ZERO
    val viaCheckout = checkout > buyLimit
    val shown = if (viaCheckout) checkout else buyLimit
    return AppleReputationSummary(
        currencyCode = currency.code,
        points = points.toString(),
        isBlacklisted = isBlacklisted,
        canBuy = shown.micros.signum() > 0,
        isAtCeiling = isAtCeiling,
        buyLimitMicros = buyLimit.micros.toString(),
        maxBuyLimitMicros = maxBuyLimit.micros.toString(),
        shownLimitMicros = shown.micros.toString(),
        isLimitFromCheckout = viaCheckout,
        isSelfieAvailable = isSelfieAvailable,
        // Declaration order, which is descending by award: LinkedIn leads every list.
        platforms =
            SocialPlatform.entries.map { platform ->
                AppleReputationPlatform(
                    id = platform.name,
                    name = platform.onChainName,
                    awardPoints = award(platform).toString(),
                    isVerified = platform in verified,
                    requiresMatureAccount = platform.requiresMatureAccount,
                    limitGainMicros = limitGainFor(platform)?.micros?.toString(),
                )
            },
    )
}
