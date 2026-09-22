// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.apple

import xyz.justzappit.offramp.liveness.LivenessStanding
import xyz.justzappit.offramp.liveness.LivenessStatus

internal fun LivenessStatus.toApple(): AppleLivenessStatus =
    when (this) {
        LivenessStatus.Preparing -> AppleLivenessStatus.Preparing
        is LivenessStatus.Ready -> AppleLivenessStatus.Ready(widgetUrl, expiresInSeconds)
        LivenessStatus.Verifying -> AppleLivenessStatus.Verifying
        LivenessStatus.Submitting -> AppleLivenessStatus.Submitting
        is LivenessStatus.Done -> AppleLivenessStatus.Done(standing.toApple())
        is LivenessStatus.Failed -> AppleLivenessStatus.Failed(reason.name)
    }

internal fun LivenessStanding.toApple() =
    AppleLivenessStanding(
        isVerified = isVerified,
        limitMicros = limit.micros.toString(),
        tierCapMicros = tierCap.micros.toString(),
    )
