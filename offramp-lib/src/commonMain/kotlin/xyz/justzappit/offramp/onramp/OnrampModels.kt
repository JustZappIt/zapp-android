// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.onramp

import kotlinx.serialization.Serializable
import xyz.justzappit.evm.types.Address
import xyz.justzappit.offramp.p2p.CurrencyCode
import xyz.justzappit.offramp.p2p.Usdc6

/**
 * Order lifecycle as the service reports it. The service owns every transition; the app never
 * advances a phase on the strength of a request it has merely sent.
 */
@Serializable
enum class OnrampPhase {
    PLACING,
    AWAITING_MERCHANT,
    AWAITING_PAYMENT,
    CONFIRMING_PAID,
    AWAITING_SETTLEMENT,
    COMPLETED,
    EXPIRED,
    CANCELLED,
    FAILED,
    ;

    val isTerminal: Boolean
        get() = this == COMPLETED || this == EXPIRED || this == CANCELLED || this == FAILED

    companion object {
        fun fromWire(value: String): OnrampPhase? = entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}

/** Corridor bounds and kill switch. Read from `/v1/config`; never hardcode the pilot caps. */
data class OnrampLimits(
    val enabled: Boolean,
    val currency: CurrencyCode,
    val minFiat: Usdc6,
    val maxFiat: Usdc6,
    val perUserDailyFiat: Usdc6,
) {
    companion object {
        val DISABLED =
            OnrampLimits(
                enabled = false,
                currency = CurrencyCode.Inr,
                minFiat = Usdc6.ZERO,
                maxFiat = Usdc6.ZERO,
                perUserDailyFiat = Usdc6.ZERO,
            )
    }
}

/**
 * A single-use price lock, roughly 90 seconds. [fiatAmount] is the service's own quantisation of
 * the requested amount, so it is what the user must be shown and charged, not what they typed.
 */
data class OnrampQuote(
    val quoteId: String,
    val currency: CurrencyCode,
    val fiatAmount: Usdc6,
    val grossUsdc: Usdc6,
    val feeUsdc: Usdc6,
    val netUsdc: Usdc6,
    val buyPrice: Usdc6,
    val expiresAtMillis: Long,
)

/**
 * How to pay the merchant, non-null only from [OnrampPhase.AWAITING_PAYMENT] onwards. Redacted in
 * [toString] because every variant carries a real payee handle.
 */
sealed interface OnrampPaymentInstruction {
    data class Upi(
        val address: String,
        val intentUrl: String,
        val amount: String,
    ) : OnrampPaymentInstruction {
        override fun toString(): String = "Upi(<redacted>)"
    }

    data class Qr(
        val payload: String,
    ) : OnrampPaymentInstruction {
        override fun toString(): String = "Qr(<redacted>)"
    }

    data class Fields(
        val fields: List<Field>,
    ) : OnrampPaymentInstruction {
        override fun toString(): String = "Fields(<redacted>)"
    }

    data class Plain(
        val address: String,
    ) : OnrampPaymentInstruction {
        override fun toString(): String = "Plain(<redacted>)"
    }

    data class Field(
        val label: String,
        val value: String,
    )
}

/**
 * The service's view of one order. [id] is the service UUID and the only handle the app persists;
 * [orderId] is the on-chain id, null until placement lands, and is for display and support only.
 */
data class OnrampOrder(
    val id: String,
    val orderId: String?,
    val phase: OnrampPhase,
    val currency: CurrencyCode,
    val fiatAmount: Usdc6?,
    val netUsdc: Usdc6?,
    val recipientAddress: Address?,
    val paymentInstruction: OnrampPaymentInstruction?,
    val placeTx: String?,
    val paidTx: String?,
    val expiresAtMillis: Long?,
    val failureCode: OnrampFailureCode?,
    val createdAtMillis: Long?,
)

/**
 * Resume point persisted across process death. The service owns the order, so this is only the
 * handle needed to re-read it: no amounts, no address, and no payment material.
 */
@Serializable
data class OnrampCheckpoint(
    val id: String,
    val phase: OnrampPhase,
    val orderId: String? = null,
) {
    init {
        require(id.isNotBlank()) { "checkpoint id must not be blank" }
    }
}
