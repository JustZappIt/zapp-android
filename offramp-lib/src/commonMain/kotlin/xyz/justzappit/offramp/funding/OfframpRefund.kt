// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.funding

import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.offramp.p2p.Usdc6

/**
 * Decides where a cancelled order's refunded USDC should go to return to the user as ZEC. The
 * orchestrator does the sponsored `USDC.transfer` itself; this seam only resolves the destination,
 * so the NEAR-specific logic stays isolated and network-toggled.
 *
 * Mainnet resolves a NEAR Intents 1-Click deposit address (USDC→ZEC, §3.6). Testnet returns null —
 * no NEAR route — and the refunded USDC simply stays in the self-custodial smart account (safe).
 */
interface OfframpRefund {
    /** Base address to send the refunded USDC to, or null to leave it in the account (testnet). */
    suspend fun pullbackTarget(account: Address, amount: Usdc6): Address?

    /** Waits for the already-funded bridge handle to deliver ZEC. Returns only on confirmed success. */
    suspend fun awaitSettlement(handle: String)

    /** Persisted before broadcasting so recovery never guesses from a later account balance. */
    suspend fun markTransferStarting(handle: String, amount: Usdc6) = Unit

    /** Persists the exact tx/user-op handle immediately after submission for receipt recovery. */
    suspend fun markTransferSubmitted(handle: String, amount: Usdc6, txHash: TxHash) = Unit
}

data class RefundResume(
    val handle: String,
    val amount: Usdc6,
    val transferStarted: Boolean = true,
    val txHash: TxHash? = null,
)

/** Testnet/dev: no NEAR route. The refunded USDC remains in the smart account (already self-custodial). */
class NoRouteOfframpRefund : OfframpRefund {
    override suspend fun pullbackTarget(account: Address, amount: Usdc6): Address? = null

    override suspend fun awaitSettlement(handle: String) = Unit
}
