// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.reputation

import xyz.justzappit.offramp.p2p.CurrencyCode
import xyz.justzappit.offramp.p2p.Usdc6
import xyz.justzappit.offramp.reputation.ReputationSummary
import xyz.justzappit.offramp.reputation.SocialPlatform

/**
 * The social accounts Raise my limit offers for [currency]. p2p.me's own client hides Binance in
 * India, so an INR user who tried it would meet a failure we could have predicted. The corridor is
 * the country signal Zapp actually has — the user is buying with rupees — and it beats a device
 * locale, which says where the phone was set up.
 */
internal fun offeredPlatforms(currency: CurrencyCode): List<SocialPlatform> =
    SocialPlatform.entries.filter { it != SocialPlatform.Binance || currency != CurrencyCode.Inr }

/**
 * The most a single social verification would add to the buy limit, from the accounts Raise my
 * limit actually offers: what the locked screen promises before the user commits to verifying.
 * Null when no offered account can state a gain honestly (see [ReputationSummary.limitGainFor]).
 */
internal fun ReputationSummary.bestSingleUnlock(currency: CurrencyCode): Usdc6? =
    offeredPlatforms(currency).mapNotNull { limitGainFor(it) }.maxOrNull()
