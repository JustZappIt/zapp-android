// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.onramp

import xyz.justzappit.offramp.p2p.Usdc6

sealed interface OnrampStatus {
    data object Idle : OnrampStatus

    data object Quoting : OnrampStatus

    data class Placing(
        val id: String?,
    ) : OnrampStatus

    data class AwaitingMerchant(
        val id: String,
        val orderId: String?,
    ) : OnrampStatus

    data class AwaitingPayment(
        val id: String,
        val orderId: String?,
        val instruction: OnrampPaymentInstruction,
        val fiatAmount: Usdc6,
        val expiresAtMillis: Long?,
    ) : OnrampStatus

    data class ConfirmingPaid(
        val id: String,
        val orderId: String?,
    ) : OnrampStatus

    data class AwaitingSettlement(
        val id: String,
        val orderId: String?,
    ) : OnrampStatus

    data class Completed(
        val id: String,
        val orderId: String?,
        val netUsdc: Usdc6,
        val fiatAmount: Usdc6,
        val paidTx: String?,
    ) : OnrampStatus

    data class Cancelled(
        val id: String?,
        val orderId: String?,
    ) : OnrampStatus

    data class Failed(
        val code: OnrampFailureCode,
        val phase: OnrampPhase,
        val id: String?,
        val orderId: String?,
    ) : OnrampStatus
}

/**
 * Every failure the app distinguishes: the `{code}` of an error response, plus the `failureCode`
 * a terminal order carries. Branch on these, never on the human-readable `message`.
 */
enum class OnrampFailureCode {
    BAD_REQUEST,
    UNAUTHENTICATED,
    NONCE_INVALID,
    RECIPIENT_NOT_ALLOWED,
    ROUTE_DISABLED,
    ORDER_NOT_FOUND,
    WRONG_PHASE,
    QUOTE_EXPIRED,
    CAP_EXCEEDED,
    SCREENING_REJECTED,
    UPSTREAM_FAILED,
    OPERATOR_UNAVAILABLE,
    NO_MERCHANT,
    ORDER_EXPIRED,
    NETWORK_UNAVAILABLE,
    UNKNOWN,
    ;

    /** Retryable without user intervention; everything else needs a new order or a new quote. */
    val isTransient: Boolean
        get() = this == UPSTREAM_FAILED || this == OPERATOR_UNAVAILABLE || this == NETWORK_UNAVAILABLE

    companion object {
        fun fromWire(value: String?): OnrampFailureCode =
            value?.let { raw -> entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } } ?: UNKNOWN
    }
}

/**
 * Whether the order survives this failure on the service. Only a transient failure leaves it
 * running, so only then may the resume checkpoint be kept.
 */
val OnrampStatus.leavesOrderAlive: Boolean
    get() = this is OnrampStatus.Failed && code.isTransient

val OnrampStatus.isTerminal: Boolean
    get() = this is OnrampStatus.Completed || this is OnrampStatus.Cancelled || this is OnrampStatus.Failed

val OnrampStatus.phase: OnrampPhase
    get() =
        when (this) {
            OnrampStatus.Idle, OnrampStatus.Quoting, is OnrampStatus.Placing -> OnrampPhase.PLACING
            is OnrampStatus.AwaitingMerchant -> OnrampPhase.AWAITING_MERCHANT
            is OnrampStatus.AwaitingPayment -> OnrampPhase.AWAITING_PAYMENT
            is OnrampStatus.ConfirmingPaid -> OnrampPhase.CONFIRMING_PAID
            is OnrampStatus.AwaitingSettlement -> OnrampPhase.AWAITING_SETTLEMENT
            is OnrampStatus.Completed -> OnrampPhase.COMPLETED
            is OnrampStatus.Cancelled -> OnrampPhase.CANCELLED
            is OnrampStatus.Failed -> phase
        }

val OnrampStatus.id: String?
    get() =
        when (this) {
            is OnrampStatus.Placing -> id
            is OnrampStatus.AwaitingMerchant -> id
            is OnrampStatus.AwaitingPayment -> id
            is OnrampStatus.ConfirmingPaid -> id
            is OnrampStatus.AwaitingSettlement -> id
            is OnrampStatus.Completed -> id
            is OnrampStatus.Cancelled -> id
            is OnrampStatus.Failed -> id
            OnrampStatus.Idle, OnrampStatus.Quoting -> null
        }

val OnrampStatus.orderId: String?
    get() =
        when (this) {
            is OnrampStatus.AwaitingMerchant -> orderId
            is OnrampStatus.AwaitingPayment -> orderId
            is OnrampStatus.ConfirmingPaid -> orderId
            is OnrampStatus.AwaitingSettlement -> orderId
            is OnrampStatus.Completed -> orderId
            is OnrampStatus.Cancelled -> orderId
            is OnrampStatus.Failed -> orderId
            OnrampStatus.Idle, OnrampStatus.Quoting, is OnrampStatus.Placing -> null
        }
