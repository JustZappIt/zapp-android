// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.orchestrator

import xyz.justzappit.evm.abi.Selector4
import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.offramp.p2p.OrderStatus
import xyz.justzappit.offramp.p2p.Usdc6

sealed class OfframpStatus {
    object Idle : OfframpStatus()

    data class SelectingCircle(
        val candidateCount: Int,
        val selectedCircleId: BigInteger? = null,
    ) : OfframpStatus()

    /**
     * Mainnet only: a ZEC→USDC NEAR bridge is in flight to fund the smart account. [depositAddress]
     * is the 1-Click handle the orchestrator persists so the bridge can be resumed after process
     * death; null until the quote returns. Testnet skips this (the account is pre-funded).
     */
    data class BridgingFunds(
        val amount: Usdc6,
        val depositAddress: String? = null,
    ) : OfframpStatus()

    /**
     * Funding step short-circuited: the smart account already held [baseBalance] ≥ the order
     * amount, so no NEAR bridge ran. Distinct from [BridgingFunds] so the UI can render
     * "Using Base balance" instead of "Bridging funds".
     */
    data class FundedFromBase(
        val amount: Usdc6,
        val baseBalance: Usdc6,
    ) : OfframpStatus()

    data class ApprovingUsdc(
        val txHash: TxHash,
        val amount: Usdc6,
    ) : OfframpStatus()

    data class PlacingOrder(
        val txHash: TxHash,
        val circleId: BigInteger,
        val amount: Usdc6,
        /** EntryPoint nonce paired with [txHash] when this is the pre-broadcast marker. */
        val submissionNonceDecimal: String? = null,
    ) : OfframpStatus()

    data class WaitingForMerchantAcceptance(
        val orderId: BigInteger,
        val pollAttempts: Int = 0,
        val lastObservedStatus: OrderStatus? = null,
        val stalled: Boolean = false,
        val expired: Boolean = false,
    ) : OfframpStatus()

    data class WaitingForPaymentDetails(
        val orderId: BigInteger,
        val merchantAddress: Address,
        val merchantPubKey: String,
        val acceptedAtEpochSeconds: Long? = null,
    ) : OfframpStatus()

    data class SendingEncryptedUpi(
        val orderId: BigInteger,
        val txHash: TxHash,
        val merchantAddress: Address,
        val merchantPubKey: String,
        val paymentAddress: String,
        val acceptedAtEpochSeconds: Long? = null,
    ) : OfframpStatus()

    data class WaitingForCompletion(
        val orderId: BigInteger,
        val pollAttempts: Int = 0,
        val lastObservedStatus: OrderStatus? = null,
        val stalled: Boolean = false,
        val expired: Boolean = false,
        val acceptedAtEpochSeconds: Long? = null,
        val paidAtEpochSeconds: Long? = null,
    ) : OfframpStatus()

    data class Completed(
        val orderId: BigInteger,
        val acceptedMerchant: Address,
        val placedAtEpochSeconds: Long? = null,
        val acceptedAtEpochSeconds: Long? = null,
        val paidAtEpochSeconds: Long? = null,
        val completedAtEpochSeconds: Long? = null,
    ) : OfframpStatus()

    /**
     * Order observed in on-chain CANCELLED status. Normal terminal: either the user called
     * cancelOrder themselves, or the executor's order-sweeper auto-cancelled after the Diamond's
     * 30-min expiry window. USDC has been refunded to the offramp account on-chain. Distinct from
     * [Failed], which signals a genuine error (revert, RPC death, etc.).
     */
    data class Cancelled(
        val orderId: BigInteger,
        val cancelledAtEpochSeconds: Long?,
        val refundedUsdcAmount: Usdc6? = null,
        val acceptedMerchant: Address? = null,
    ) : OfframpStatus()

    /**
     * Terminal state for the funded-but-unplaced recovery: a mainnet funding bridge delivered USDC
     * but the order was never placed (e.g. the route vanished or placeOrder reverted), so the user
     * pulled the stranded USDC back out of the smart account. [amount] is what was recovered;
     * [target] is the pull-back destination (a NEAR deposit address on mainnet), null if the USDC was
     * left in the self-custodial account (testnet / no route).
     */
    data class FundsRecovered(
        val amount: Usdc6,
        val target: Address? = null,
        val txHash: TxHash? = null,
    ) : OfframpStatus()

    data class Failed(
        val message: String,
        val orderId: BigInteger?,
        val step: OfframpStep,
        val txHash: TxHash? = null,
        val revertSelector: Selector4? = null,
        val knownRevertReason: KnownRevertReason? = null,
        /**
         * Long-tail SDK error name from [KnownContractErrors] when [knownRevertReason] is null but
         * the selector is one the SDK recognises. Lets the UI render "Contract error: FOO" instead
         * of dumping the raw 4-byte selector for the ~115 errors we don't curate.
         */
        val sdkErrorName: String? = null,
        /**
         * Human-readable English copy for [sdkErrorName] from [KnownContractErrors]. The UI shows
         * this for the long tail so the user reads "Order expired" rather than the raw
         * `ORDER_EXPIRED` code; `null` when the selector is outside the SDK table.
         */
        val sdkErrorMessage: String? = null,
        val solidityErrorString: String? = null,
        /** True only when placeOrder was definitely rejected or its included receipt reverted. */
        val nothingEscrowed: Boolean = false,
        val cause: Throwable? = null,
    ) : OfframpStatus()
}

/**
 * Canonical state-machine step. Shared between orchestrator state tracking, [OfframpStatus.Failed.step],
 * and the UI progress indicator — no parallel encodings.
 */
enum class OfframpStep {
    INITIALIZATION,
    SELECTING_CIRCLE,
    FUNDING,
    APPROVING_USDC,
    PLACING_ORDER,
    WAITING_FOR_ACCEPTANCE,
    WAITING_FOR_PAYMENT_DETAILS,
    ENCRYPTING_UPI,
    SENDING_UPI,
    WAITING_FOR_COMPLETION,
    ;

    companion object {
        /** Steps surfaced to the UI progress indicator (skips INITIALIZATION + ENCRYPTING_UPI). */
        val UI_PROGRESS: List<OfframpStep> =
            listOf(
                SELECTING_CIRCLE,
                FUNDING,
                APPROVING_USDC,
                PLACING_ORDER,
                WAITING_FOR_ACCEPTANCE,
                WAITING_FOR_PAYMENT_DETAILS,
                SENDING_UPI,
                WAITING_FOR_COMPLETION,
            )
    }
}

/** The on-chain order id once the flow has placed one, else null (pre-order steps + Idle). */
val OfframpStatus.orderId: BigInteger? get() =
    when (this) {
        is OfframpStatus.WaitingForMerchantAcceptance -> orderId

        is OfframpStatus.WaitingForPaymentDetails -> orderId

        is OfframpStatus.SendingEncryptedUpi -> orderId

        is OfframpStatus.WaitingForCompletion -> orderId

        is OfframpStatus.Completed -> orderId

        is OfframpStatus.Cancelled -> orderId

        is OfframpStatus.Failed -> orderId

        OfframpStatus.Idle,
        is OfframpStatus.SelectingCircle,
        is OfframpStatus.BridgingFunds,
        is OfframpStatus.FundedFromBase,
        is OfframpStatus.FundsRecovered,
        is OfframpStatus.ApprovingUsdc,
        is OfframpStatus.PlacingOrder -> null
    }

/** Derives the canonical [OfframpStep] from any [OfframpStatus] instance. */
val OfframpStatus.step: OfframpStep get() =
    when (this) {
        OfframpStatus.Idle -> OfframpStep.INITIALIZATION
        is OfframpStatus.SelectingCircle -> OfframpStep.SELECTING_CIRCLE
        is OfframpStatus.BridgingFunds -> OfframpStep.FUNDING
        is OfframpStatus.FundedFromBase -> OfframpStep.FUNDING
        is OfframpStatus.FundsRecovered -> OfframpStep.FUNDING
        is OfframpStatus.ApprovingUsdc -> OfframpStep.APPROVING_USDC
        is OfframpStatus.PlacingOrder -> OfframpStep.PLACING_ORDER
        is OfframpStatus.WaitingForMerchantAcceptance -> OfframpStep.WAITING_FOR_ACCEPTANCE
        is OfframpStatus.WaitingForPaymentDetails -> OfframpStep.WAITING_FOR_PAYMENT_DETAILS
        is OfframpStatus.SendingEncryptedUpi -> OfframpStep.SENDING_UPI
        is OfframpStatus.WaitingForCompletion -> OfframpStep.WAITING_FOR_COMPLETION
        is OfframpStatus.Completed -> OfframpStep.WAITING_FOR_COMPLETION
        is OfframpStatus.Cancelled -> OfframpStep.WAITING_FOR_COMPLETION
        is OfframpStatus.Failed -> this.step
    }
