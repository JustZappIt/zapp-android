// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.onramp

import kotlinx.coroutines.flow.Flow
import xyz.justzappit.evm.types.Address
import xyz.justzappit.offramp.p2p.Usdc6

interface OnrampZecDeliveryDriver {
    fun deliver(
        orderId: String,
        recipient: Address,
        amount: Usdc6,
        resume: OnrampZecDeliveryCheckpoint? = null,
    ): Flow<OnrampZecDeliveryStatus>
}

sealed interface OnrampZecDeliveryStatus {
    data class Preparing(
        val usdc: Usdc6,
    ) : OnrampZecDeliveryStatus

    data class Submitting(
        val usdc: Usdc6,
    ) : OnrampZecDeliveryStatus

    data class AwaitingZec(
        val usdc: Usdc6,
    ) : OnrampZecDeliveryStatus

    data class Delivered(
        val inputUsdc: Usdc6,
        val outputZec: String,
        val baseTransactionHash: String?,
    ) : OnrampZecDeliveryStatus

    data class RefundedToBase(
        val inputUsdc: Usdc6,
        val refundedUsdc: Usdc6,
        val baseAccount: Address,
    ) : OnrampZecDeliveryStatus {
        override fun toString(): String =
            "RefundedToBase(inputUsdc=$inputUsdc, refundedUsdc=$refundedUsdc, baseAccount=<redacted>)"
    }

    data class Failed(
        val stage: OnrampZecDeliveryPhase,
        val fundsLocation: FundsLocation,
        val retryable: Boolean,
    ) : OnrampZecDeliveryStatus
}

enum class FundsLocation {
    BASE_ACCOUNT,
    RECIPIENT_MISMATCH,
    TRANSFER_AMBIGUOUS,
    NEAR_INTENT,
    ZCASH_WALLET,
    BASE_REFUND_CONFIRMED,
}

/**
 * The durable checkpoint outranks the exception: only it can say whether the transfer was started,
 * so a failure raised outside the driver is described from state rather than from a message.
 */
fun onrampDeliveryFailure(
    latest: OnrampZecDeliveryCheckpoint?,
    resumed: OnrampZecDeliveryCheckpoint? = null,
) = OnrampZecDeliveryStatus.Failed(
    stage = latest?.phase ?: resumed?.phase ?: OnrampZecDeliveryPhase.NEEDS_ATTENTION,
    fundsLocation = latest?.fundsLocation ?: FundsLocation.TRANSFER_AMBIGUOUS,
    retryable = latest != null && !latest.transferStarted,
)

/** A confirmed refund is a new starting balance on Base, not the amount the order settled for. */
fun OnrampZecDeliveryCheckpoint.restartedAfterRefund() =
    OnrampZecDeliveryCheckpoint(
        phase = OnrampZecDeliveryPhase.FUNDS_ON_BASE,
        usdcMicros = requireNotNull(refundedUsdcMicros),
        baseAccount = baseAccount,
        acceptedCostBps = acceptedCostBps,
    )

/**
 * Where a checkpoint proves the money is, from durable evidence alone. A mined Base transaction is
 * the only proof the deposit reached the intent; a started-but-unproven transfer stays ambiguous so
 * no caller can claim either side of it.
 */
val OnrampZecDeliveryCheckpoint.fundsLocation: FundsLocation
    get() =
        when {
            baseTransactionHash != null -> FundsLocation.NEAR_INTENT
            transferStarted -> FundsLocation.TRANSFER_AMBIGUOUS
            else -> FundsLocation.BASE_ACCOUNT
        }
