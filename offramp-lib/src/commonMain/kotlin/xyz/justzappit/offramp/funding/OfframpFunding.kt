// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.funding

import xyz.justzappit.evm.rpc.BaseRpcClient
import xyz.justzappit.evm.types.Address
import xyz.justzappit.offramp.orchestrator.OfframpRequest
import xyz.justzappit.offramp.p2p.Usdc6
import xyz.justzappit.offramp.p2p.getUsdcBalance

/**
 * What the funding seam ended up doing. Lets the orchestrator surface a distinct UI status for the
 * skipped-bridge path (the smart account already held enough USDC — common after a cancelled order
 * left USDC refunded into the account) vs. the actually-bridged path.
 */
sealed interface FundingOutcome {
    /** Account already held [currentBalance] ≥ the order amount; no bridge ran. */
    data class AlreadyFunded(
        val currentBalance: Usdc6
    ) : FundingOutcome

    /** A bridge was opened at [depositAddress] and settled successfully. */
    data class Bridged(
        val depositAddress: String
    ) : FundingOutcome
}

/**
 * Makes the smart account hold the USDC an order needs. Called by the orchestrator **after** the
 * circle-eligibility gate and **before** approve/placeOrder, so we never fund (bridge) into a market
 * with no merchant. Network-toggled in DI: [PreFundedOfframpFunding] on testnet (expects manual
 * funding); on mainnet a NEAR-bridge implementation that lives in ui-lib and reuses the app's
 * existing 1-Click swap stack (it needs the wallet + `SwapDataSource`, which offramp-lib can't see).
 *
 * The mainnet bridge is asynchronous (quote → ZEC deposit → poll until USDC lands), so this seam is
 * **resumable and must be idempotent**:
 *
 *  - [resumeHandle] is the 1-Click deposit address persisted on a prior attempt, or null on a fresh
 *    start. A non-null handle MUST be resumed (re-polled to completion), never re-quoted — re-quoting
 *    opens a second bridge and the user double-sends ZEC.
 *  - [onBridgeStarted] is invoked with the deposit address the instant a bridge is opened (after the
 *    quote, before any ZEC moves) so the orchestrator can persist it first, closing the
 *    crash-during-deposit window.
 *
 * Implementations return a [FundingOutcome] only once [account] verifiably holds at least
 * `request.usdcAmount`; otherwise they throw (fail-closed — the order is never placed unfunded).
 */
fun interface OfframpFunding {
    suspend fun ensureFunded(
        account: Address,
        request: OfframpRequest,
        resumeHandle: String?,
        onBridgeStarted: suspend (depositAddress: String) -> Unit,
    ): FundingOutcome
}

/**
 * Testnet/dev funding: no bridge (NEAR has no testnet route). Verifies the account already holds the
 * order amount and fails fast with an actionable message otherwise. The resume/bridge parameters are
 * unused — there is nothing to resume when funding is manual.
 */
class PreFundedOfframpFunding(
    private val rpc: BaseRpcClient,
    private val usdc: Address,
) : OfframpFunding {
    override suspend fun ensureFunded(
        account: Address,
        request: OfframpRequest,
        resumeHandle: String?,
        onBridgeStarted: suspend (depositAddress: String) -> Unit,
    ): FundingOutcome {
        val balance = rpc.getUsdcBalance(usdc, account)
        check(balance >= request.usdcAmount) {
            "Smart account ${account.checksumHex} holds ${balance.micros} USDC (micros), needs " +
                "${request.usdcAmount.micros}. Fund it directly — no bridge on testnet."
        }
        return FundingOutcome.AlreadyFunded(currentBalance = balance)
    }
}
