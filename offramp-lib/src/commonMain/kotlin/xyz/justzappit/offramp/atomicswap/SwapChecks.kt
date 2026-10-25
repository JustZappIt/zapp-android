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
            zcashNetwork == deployment.zcashNetwork &&
            fitsWindows(contractKind, minWindow, lateWindow, deployment.windows)
    if (!serves || (reverse && !reverseEnabled)) {
        throw AtomicSwapBlockedException(AtomicSwapBlock.WRONG_DEPLOYMENT, "the maker serves another deployment")
    }
    // A return key shown to only some devices would mark the tokens they get back.
    val returnKey = tokenReturnKey
    if (returnKey != null && returnKey != deployment.tokenIssuer?.returnKey) {
        throw AtomicSwapBlockedException(AtomicSwapBlock.TOKENS_REFUSED, "the maker returns tokens under another key")
    }
}

/** The fee these terms ask, once they're [deployment]'s own relayer's, not its maker's, on its contract's windows. */
internal fun RelayerTerms.checkedFee(deployment: SwapDeployment): Usdc6 {
    val serves =
        chainId == deployment.chainId &&
            contract == deployment.contract &&
            relayer == deployment.relayer &&
            relayer != deployment.maker &&
            fitsWindows(contractKind, minWindow, lateWindow, deployment.windows)
    if (!serves) throw AtomicSwapBlockedException(AtomicSwapBlock.WRONG_DEPLOYMENT, "another deployment's relayer")
    return fee
}

/** As [checkedFee], once it leaves something of a payout of [amount]. */
internal fun RelayerTerms.checkedFee(
    deployment: SwapDeployment,
    amount: Usdc6,
): Usdc6 = checkedFee(deployment).also { requirePaysOut(amount, it) }

/** The relayer refuses a payout of [amount] that [fee] would take all of, so nothing that needs one starts. */
internal fun requirePaysOut(
    amount: Usdc6,
    fee: Usdc6
) {
    if (fee >= amount) throw AtomicSwapBlockedException(AtomicSwapBlock.RELAYER_FEE, "a fee of $fee leaves nothing")
}

/**
 * Whether what a service says of its contract fits the deployment's [windows], as `zecswap_api::fits_windows` has it:
 * what it doesn't say fits, as the contract itself is read either way, and a service of a lock contract says none.
 */
internal fun fitsWindows(
    kind: String?,
    minWindow: Long?,
    lateWindow: Long?,
    windows: SwapWindows?,
): Boolean =
    if (windows == null) {
        kind == null && minWindow == null && lateWindow == null
    } else {
        (kind == null || kind == WINDOWS_CONTRACT) &&
            (minWindow == null || minWindow == windows.min) &&
            (lateWindow == null || lateWindow == windows.late)
    }

private const val MAKER_API_VERSION = 1

/** What the services call the windows contract (`contractKind`). */
private const val WINDOWS_CONTRACT = "windows"
