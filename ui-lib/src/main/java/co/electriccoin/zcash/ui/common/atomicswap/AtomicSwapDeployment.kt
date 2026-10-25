// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.atomicswap

import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.zcash.ui.common.privateusd.RailgunSendsPin
import co.electriccoin.zcash.ui.common.provider.ZcashNetworkProvider
import xyz.justzappit.offramp.atomicswap.SwapDeployment
import xyz.justzappit.offramp.atomicswap.ZcashDepositTerms
import xyz.justzappit.offramp.deposits.DepositsPin
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
    /** Railscan, Railgun's explorer, on the same chain. */
    val railscanTxUrl: String,
    /** How long Railgun screens a payout before it can be spent. */
    val screeningTime: Duration,
    /** The relayer's terms for private sends this app accepts; none sends nothing. */
    val railgunSends: RailgunSendsPin? = null,
    /** When the maker sets a forward swap's deadlines, for the review to tell; none says nothing of them. */
    val makerDeadlines: MakerDeadlines? = null,
    /**
     * Where public USDC sent to the wallet's deposit addresses is shielded: the Deposits contract, its token and the
     * relayer's deposits account, which the relayer's terms must name; none takes no deposits. Not [deposits], the
     * maker's timing of a conversion's ZEC deposit.
     */
    val usdcDeposits: DepositsPin? = null,
) {
    /** Zcash confirmations the maker waits for before it marks a deposit ready. */
    val makerConfirmations: Int get() = swap.zcashConfirmations

    /** Roughly from confirming to the payout landing: opening, the maker's confirmations, the claim. */
    val expectedDuration: Duration get() = ZCASH_BLOCK_TIME * makerConfirmations + OPEN_AND_CLAIM
}

/**
 * How long after the open a forward swap's maker sets `t0`, from which a swap it never made ready is the user's to
 * claim, and `t1`, from which an unclaimed one is its to refund (zecSwap's maker `[timing]`). The swap's own deadlines
 * decide; these only say what to expect.
 */
data class MakerDeadlines(
    val t0: Duration,
    val t1: Duration,
)

// Before NU7, which cuts it to 25 s.
private val ZCASH_BLOCK_TIME = 75.seconds
private val OPEN_AND_CLAIM = 2.minutes

/** The deployment this build's network settles on. */
class AtomicSwapDeployments(
    zcashNetworkProvider: ZcashNetworkProvider,
) {
    val current: AtomicSwapDeployment? =
        when (zcashNetworkProvider()) {
            ZcashNetwork.Mainnet -> AtomicSwapMainnet.deployment
            ZcashNetwork.Testnet -> AtomicSwapTestnet.deployment
            else -> null
        }
}
