// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.apple

import xyz.justzappit.offramp.reclaim.ReclaimStatus
import xyz.justzappit.offramp.reputation.ReputationSummary
import xyz.justzappit.offramp.reputation.SocialPlatform

internal fun ReclaimStatus.toApple(): AppleReclaimStatus =
    when (this) {
        ReclaimStatus.Preparing -> AppleReclaimStatus.Preparing

        // Only the request URL crosses. The other two links are Play's, and iOS's own fallback
        // belongs on the Swift side, next to the store it names.
        is ReclaimStatus.Ready -> AppleReclaimStatus.Ready(requestUrl)

        ReclaimStatus.Verifying -> AppleReclaimStatus.Verifying

        ReclaimStatus.Submitting -> AppleReclaimStatus.Submitting

        is ReclaimStatus.Done -> AppleReclaimStatus.Done(summary.toApple())

        is ReclaimStatus.Failed -> AppleReclaimStatus.Failed(reason.name)
    }

internal fun ReputationSummary.toApple() =
    AppleReputationSummary(
        currencyCode = currency.code,
        points = points.toString(),
        isBlacklisted = isBlacklisted,
        canBuy = canBuy,
        isAtCeiling = isAtCeiling,
        buyLimitMicros = buyLimit.micros.toString(),
        maxBuyLimitMicros = maxBuyLimit.micros.toString(),
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
