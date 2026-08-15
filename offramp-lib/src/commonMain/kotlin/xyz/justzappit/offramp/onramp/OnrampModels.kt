// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.onramp

import kotlinx.serialization.Serializable
import xyz.justzappit.evm.math.BigInteger
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

@Serializable
enum class OnrampZecDeliveryPhase {
    FUNDS_ON_BASE,
    QUOTING,
    QUOTE_READY,
    TRANSFER_STARTING,
    TRANSFER_SUBMITTED,
    AWAITING_ZEC,
    DELIVERED,
    REFUNDED_TO_BASE,
    NEEDS_ATTENTION,
}

@Serializable
enum class OnrampDestination {
    ZCASH,
    BASE,
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

/** P2P resume handle and optional ZEC delivery recovery state. Payment material is never stored. */
@Serializable
data class OnrampCheckpoint(
    val id: String,
    val phase: OnrampPhase,
    val orderId: String? = null,
    val destination: OnrampDestination = OnrampDestination.BASE,
    val zecDelivery: OnrampZecDeliveryCheckpoint? = null,
) {
    init {
        require(id.isNotBlank()) { "checkpoint id must not be blank" }
        require(destination == OnrampDestination.ZCASH || zecDelivery == null) {
            "Base delivery cannot contain a ZEC delivery checkpoint"
        }
    }
}

@Serializable
data class OnrampZecDeliveryCheckpoint(
    val version: Int = VERSION,
    val phase: OnrampZecDeliveryPhase,
    val usdcMicros: String,
    val baseAccount: String,
    val zcashRecipient: String? = null,
    val depositAddress: String? = null,
    val quoteDeadlineMillis: Long? = null,
    val transferStarted: Boolean = false,
    val userOperationHash: String? = null,
    val baseTransactionHash: String? = null,
    val outputZec: String? = null,
    val refundedUsdcMicros: String? = null,
    val acceptedCostBps: Int? = null,
) {
    init {
        require(version == VERSION) { "unsupported ZEC delivery checkpoint version" }
        require(acceptedCostBps == null || acceptedCostBps in 0..MAX_BPS) { "accepted conversion cost is invalid" }
        require(usdcMicros.toPositiveUsdc6OrNull() != null) { "delivery amount must be positive" }
        require(Address.parseOrNull(baseAccount) != null) { "delivery Base account is invalid" }
        require(userOperationHash == null || userOperationHash.isNotBlank()) {
            "delivery UserOperation hash is invalid"
        }
        require(baseTransactionHash == null || baseTransactionHash.isNotBlank()) {
            "delivery Base transaction hash is invalid"
        }
        require(outputZec == null || outputZec.isNotBlank()) { "delivery ZEC output is invalid" }
        require(refundedUsdcMicros == null || refundedUsdcMicros.toPositiveUsdc6OrNull() != null) {
            "delivery USDC refund is invalid"
        }
        if (phase == OnrampZecDeliveryPhase.DELIVERED) {
            require(outputZec != null) { "delivered checkpoint is missing its ZEC output" }
        }
        if (phase == OnrampZecDeliveryPhase.REFUNDED_TO_BASE) {
            require(refundedUsdcMicros != null) { "refunded checkpoint is missing its USDC amount" }
            require(Usdc6(BigInteger(refundedUsdcMicros)) <= Usdc6(BigInteger(usdcMicros))) {
                "delivery USDC refund exceeds its input"
            }
        }

        if (phase.requiresQuote) {
            require(!zcashRecipient.isNullOrBlank()) { "delivery Zcash recipient is missing" }
            require(Address.parseOrNull(depositAddress.orEmpty()) != null) { "delivery deposit address is invalid" }
            require(quoteDeadlineMillis != null && quoteDeadlineMillis > 0) { "delivery quote deadline is invalid" }
        }
        if (phase.requiresTransferStart) {
            require(transferStarted) { "delivery transfer start was not recorded" }
        }
        if (phase == OnrampZecDeliveryPhase.TRANSFER_SUBMITTED) {
            require(userOperationHash != null) { "delivery UserOperation hash is missing" }
        }
        if (phase.requiresConfirmedBaseTransfer) {
            require(baseTransactionHash != null) { "delivery Base transaction hash is missing" }
        }
    }

    override fun toString(): String =
        "OnrampZecDeliveryCheckpoint(version=$version, phase=$phase, transferStarted=$transferStarted, " +
            "hasUserOperationHash=${userOperationHash != null}, hasBaseTransactionHash=${baseTransactionHash != null})"

    companion object {
        const val VERSION = 1
        const val MAX_BPS = 10_000
    }
}

private val OnrampZecDeliveryPhase.requiresQuote: Boolean get() = this in QUOTE_PHASES

private val OnrampZecDeliveryPhase.requiresTransferStart: Boolean get() = this in TRANSFER_STARTED_PHASES

private val OnrampZecDeliveryPhase.requiresConfirmedBaseTransfer: Boolean get() = this in CONFIRMED_TRANSFER_PHASES

private fun String.toPositiveUsdc6OrNull(): Usdc6? =
    runCatching { Usdc6(BigInteger(this)) }
        .getOrNull()
        ?.takeIf { it > Usdc6.ZERO }

private val QUOTE_PHASES =
    setOf(
        OnrampZecDeliveryPhase.QUOTE_READY,
        OnrampZecDeliveryPhase.TRANSFER_STARTING,
        OnrampZecDeliveryPhase.TRANSFER_SUBMITTED,
        OnrampZecDeliveryPhase.AWAITING_ZEC,
        OnrampZecDeliveryPhase.DELIVERED,
        OnrampZecDeliveryPhase.REFUNDED_TO_BASE,
        OnrampZecDeliveryPhase.NEEDS_ATTENTION,
    )

private val TRANSFER_STARTED_PHASES = QUOTE_PHASES - OnrampZecDeliveryPhase.QUOTE_READY

private val CONFIRMED_TRANSFER_PHASES =
    setOf(
        OnrampZecDeliveryPhase.AWAITING_ZEC,
        OnrampZecDeliveryPhase.DELIVERED,
        OnrampZecDeliveryPhase.REFUNDED_TO_BASE,
    )
