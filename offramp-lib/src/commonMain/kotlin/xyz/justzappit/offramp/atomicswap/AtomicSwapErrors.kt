// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.atomicswap

enum class AtomicSwapService { MAKER, RELAYER }

/** A service's refusal, or with a null [status], a service that couldn't be reached. */
class AtomicSwapHttpException(
    message: String,
    val service: AtomicSwapService,
    val status: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

/** A step that can't go ahead now; running it again later may. */
class AtomicSwapBlockedException(
    val reason: AtomicSwapBlock,
    message: String,
) : IllegalStateException(message)

enum class AtomicSwapBlock {
    SWAP_UNDER_WAY,
    QUOTE_EXPIRED,
    WRONG_DEPLOYMENT,
    RELAYER_FEE,
    RAILGUN_CLOSED,
    CLAIM_LOCK_LAPSING,
    CHAIN_LAGGING,
    UNDER_WAY_ON_CHAIN,
}
