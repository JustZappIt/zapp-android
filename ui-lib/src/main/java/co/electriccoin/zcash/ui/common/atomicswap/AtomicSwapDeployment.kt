// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.atomicswap

import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.zcash.ui.common.provider.ZcashNetworkProvider
import xyz.justzappit.offramp.atomicswap.AtomicSwapConfig
import xyz.justzappit.railgun.RailgunNetwork
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

data class AtomicSwapDeployment(
    val config: AtomicSwapConfig,
    val railgunNetwork: RailgunNetwork,
    val ethereumRpcUrl: String,
    val explorerTxUrl: String,
    /** Token base units in one quoted unit. */
    val unitBaseUnits: Long,
    val minUnits: Int,
    val maxUnits: Int,
    /** Zcash confirmations the maker waits for before it marks a deposit ready. */
    val makerConfirmations: Int,
    /** How long Railgun screens a payout before it can be spent. */
    val screeningTime: Duration,
) {
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
