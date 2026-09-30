// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.atomicswap

import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.zcash.ui.common.provider.ZcashNetworkProvider
import xyz.justzappit.offramp.atomicswap.SwapDeployment
import xyz.justzappit.offramp.atomicswap.ZcashDepositTerms
import xyz.justzappit.offramp.p2p.Usdc6
import xyz.justzappit.railgun.RailgunNetwork
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** A deployment conversions run on either way, with what the app offers on it. */
data class AtomicSwapDeployment(
    val swap: SwapDeployment,
    val deposits: ZcashDepositTerms,
    val railgunNetwork: RailgunNetwork,
    val explorerTxUrl: String,
    val minAmount: Usdc6,
    val maxAmount: Usdc6,
    /** How long Railgun screens a payout before it can be spent. */
    val screeningTime: Duration,
) {
    /** Zcash confirmations the maker waits for before it marks a deposit ready. */
    val makerConfirmations: Int get() = swap.zcashConfirmations

    /** Roughly from confirming to the payout landing: opening, the maker's confirmations, the claim. */
    val expectedDuration: Duration get() = ZCASH_BLOCK_TIME * makerConfirmations + OPEN_AND_CLAIM
}

private val ZCASH_BLOCK_TIME = 75.seconds
private val OPEN_AND_CLAIM = 2.minutes

/** The deployment this build's network settles on; mainnet builds have none yet. */
class AtomicSwapDeployments(
    zcashNetworkProvider: ZcashNetworkProvider,
) {
    val current: AtomicSwapDeployment? =
        if (zcashNetworkProvider() == ZcashNetwork.Testnet) AtomicSwapTestnet.deployment else null
}
