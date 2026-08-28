// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.orchestrator

/**
 * The answer to "can this exact order be assigned to a merchant right now?".
 *
 * Three states rather than a boolean, because "every circle refused this amount" and "the chain
 * could not be reached" call for opposite handling and a boolean cannot tell them apart. Refused is
 * a fact about liquidity worth showing the user; unreachable says nothing at all, and must not be
 * allowed to look like a refusal.
 */
sealed interface MerchantAvailability {
    /** At least one eligible circle would assign a merchant for the exact amount asked about. */
    data object Available : MerchantAvailability

    /**
     * The check ran to completion and every eligible circle refused — or the corridor had no
     * eligible circle to begin with. The order would dead-end, so it is worth saying so.
     */
    data object Unavailable : MerchantAvailability

    /**
     * The check could not be completed (subgraph or RPC failure). Carries no information about
     * liquidity: callers must fall open and leave the order to the router, exactly as if they had
     * never asked.
     */
    data class Undetermined(
        val cause: Throwable
    ) : MerchantAvailability
}
