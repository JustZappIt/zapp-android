// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.atomicswap

import xyz.justzappit.offramp.p2p.Usdc6

/** Throws unless this maker serves [deployment], and takes reverse swaps too where [reverse]. */
internal fun MakerInfo.requireServing(
    deployment: SwapDeployment,
    reverse: Boolean
) {
    val serves =
        apiVersion == MAKER_API_VERSION &&
            maker == deployment.maker &&
            chainId == deployment.chainId &&
            contract == deployment.contract &&
            token == deployment.token &&
            zcashNetwork == deployment.zcashNetwork
    if (!serves || (reverse && !reverseEnabled)) {
        throw AtomicSwapBlockedException(AtomicSwapBlock.WRONG_DEPLOYMENT, "the maker serves another deployment")
    }
}

/** The fee these terms ask, once they're [deployment]'s own relayer's, not its maker's, and within [limit]. */
internal fun RelayerTerms.checkedFee(
    deployment: SwapDeployment,
    amount: Usdc6,
    limit: Usdc6 = deployment.maxRelayerFee,
): Usdc6 {
    val serves =
        chainId == deployment.chainId &&
            contract == deployment.contract &&
            relayer == deployment.relayer &&
            relayer != deployment.maker
    if (!serves) throw AtomicSwapBlockedException(AtomicSwapBlock.WRONG_DEPLOYMENT, "another deployment's relayer")
    if (fee > limit || fee >= amount) {
        throw AtomicSwapBlockedException(AtomicSwapBlock.RELAYER_FEE, "the relayer asks a fee of $fee")
    }
    return fee
}

/** Whether the contract gives us a lock at [now]: none is held, nor is it the other side's turn after ours lapsed. */
internal fun mayTakeLock(
    own: Long,
    other: Long,
    now: Long,
    lockDuration: Long,
): Boolean {
    val isHeld = now < own || now < other
    val isTheirTurn = own > other && now < own + lockDuration
    return !isHeld && !isTheirTurn
}

private const val MAKER_API_VERSION = 1
