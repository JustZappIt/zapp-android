// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.orchestrator

import xyz.justzappit.offramp.p2p.Usdc6

/**
 * Progress of a standalone "top up Base" bridge ([OfframpDriver.bridgeToBase]). Kept separate from
 * [OfframpStatus] because a top-up has no order, no circle, and no merchant — conflating the two
 * state machines would force every order-step consumer to handle bridge-only states and vice versa.
 */
sealed interface BridgeToBaseStatus {
    data object Idle : BridgeToBaseStatus

    /** ZEC→USDC bridge in flight. [depositAddress] is the 1-Click handle, null until the quote returns. */
    data class Bridging(
        val amount: Usdc6,
        val depositAddress: String? = null,
    ) : BridgeToBaseStatus

    /** Bridge settled; the account now holds [baseBalance], having added [addedAmount]. */
    data class Complete(
        val addedAmount: Usdc6,
        val baseBalance: Usdc6,
    ) : BridgeToBaseStatus

    /**
     * Bridge failed. [depositAddress] is non-null once a bridge was opened (so a transient failure
     * can be resumed); [cause] lets the UI distinguish a terminal 1-Click failure from a retryable one.
     */
    data class Failed(
        val message: String,
        val depositAddress: String? = null,
        val cause: Throwable? = null,
    ) : BridgeToBaseStatus
}
