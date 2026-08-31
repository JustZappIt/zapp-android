// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.orchestrator

import kotlinx.serialization.Serializable
import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.math.plus
import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.offramp.p2p.CurrencyCode
import xyz.justzappit.offramp.p2p.Usdc6

/**
 * Resumable snapshot of an in-flight offramp order. Persisted after every on-chain checkpoint so
 * that process death between broadcasts doesn't orphan the user's USDC.
 *
 * Wire format intentionally stores the orderId + amount as decimal strings: encrypted preferences
 * are human-readable enough to triage and we don't want a serialization-library upgrade silently
 * changing how `BigInteger`/`Usdc6` round-trip. [orderIdBig] / [usdcAmount] convert on read; the
 * primary constructor validates eagerly so a corrupt blob fails at decode time, not later inside
 * the resume path.
 */
@Serializable
data class OfframpCheckpoint(
    val orderId: String?,
    val currentStep: OfframpStep,
    /**
     * 1-Click deposit address of an in-flight ZEC→USDC bridge (mainnet), persisted the moment the
     * bridge opens — before any ZEC moves. On resume a non-null value is re-polled to completion
     * rather than re-quoted, so a crash mid-bridge can never open a second bridge (double-send).
     * Null on testnet (pre-funded) and once the order has been placed.
     */
    val bridgeDepositAddress: String? = null,
    val approveTxHash: TxHash? = null,
    val placeOrderTxHash: TxHash? = null,
    val placeOrderNonceDecimal: String? = null,
    val setUpiTxHash: TxHash? = null,
    val recipientUpi: String,
    val usdcAmountMicroDecimal: String,
    /** Quoted fee/total debit authorized by the host reservation; null only for legacy records. */
    val authorizedPayFeeMicroDecimal: String? = null,
    val authorizedRequiredBalanceMicroDecimal: String? = null,
    /**
     * Nullable for back-compat with checkpoints written before fiat was tracked; resume falls back
     * to live sellPrice.
     */
    val fiatAmountMicroDecimal: String? = null,
    val fiatAmountLimitMicroDecimal: String? = null,
    val payeeName: String? = null,
    val currency: CurrencyCode,
    val createdAtMillis: Long,
) {
    init {
        // Validate at construction so deserialization (which uses this same constructor) surfaces
        // a corrupt blob immediately rather than during the next resume() call.
        if (orderId != null) {
            val parsed = runCatching { BigInteger(orderId!!) }.getOrNull()
            require(parsed != null && parsed.signum() >= 0) {
                "OfframpCheckpoint.orderId must be a nonnegative decimal integer, got '$orderId'"
            }
        }
        val amount = runCatching { BigInteger(usdcAmountMicroDecimal) }.getOrNull()
        require(amount != null && amount.signum() > 0) {
            "OfframpCheckpoint.usdcAmountMicroDecimal must be a positive decimal integer, got '$usdcAmountMicroDecimal'"
        }
        require((authorizedPayFeeMicroDecimal == null) == (authorizedRequiredBalanceMicroDecimal == null)) {
            "OfframpCheckpoint authorized fee and required balance must be supplied together"
        }
        if (authorizedPayFeeMicroDecimal != null && authorizedRequiredBalanceMicroDecimal != null) {
            val fee = runCatching { BigInteger(authorizedPayFeeMicroDecimal!!) }.getOrNull()
            val required = runCatching { BigInteger(authorizedRequiredBalanceMicroDecimal!!) }.getOrNull()
            require(fee != null && fee.signum() >= 0) {
                "OfframpCheckpoint.authorizedPayFeeMicroDecimal must be a nonnegative integer"
            }
            require(required != null && required == checkNotNull(amount) + fee) {
                "OfframpCheckpoint.authorizedRequiredBalanceMicroDecimal must equal amount plus fee"
            }
        }
        if (fiatAmountMicroDecimal != null) {
            require(runCatching { BigInteger(fiatAmountMicroDecimal!!) }.getOrNull()?.signum()?.let { it > 0 } == true) {
                "OfframpCheckpoint.fiatAmountMicroDecimal must be a positive decimal integer, got '$fiatAmountMicroDecimal'"
            }
        }
        if (fiatAmountLimitMicroDecimal != null) {
            require(
                runCatching { BigInteger(fiatAmountLimitMicroDecimal!!) }.getOrNull()?.signum()?.let { it >= 0 } == true,
            ) {
                "OfframpCheckpoint.fiatAmountLimitMicroDecimal must be a nonnegative decimal integer, " +
                    "got '$fiatAmountLimitMicroDecimal'"
            }
        }
        if (placeOrderNonceDecimal != null) {
            val nonce = runCatching { BigInteger(placeOrderNonceDecimal!!) }.getOrNull()
            require(nonce != null && nonce.signum() >= 0) {
                "OfframpCheckpoint.placeOrderNonceDecimal must be a nonnegative integer"
            }
        }
    }

    val orderIdBig: BigInteger? get() = orderId?.let(::BigInteger)
    val usdcAmount: Usdc6 get() = Usdc6(BigInteger(usdcAmountMicroDecimal))
    val authorizedPayFee: Usdc6? get() = authorizedPayFeeMicroDecimal?.let { Usdc6(BigInteger(it)) }
    val authorizedRequiredBalance: Usdc6?
        get() = authorizedRequiredBalanceMicroDecimal?.let { Usdc6(BigInteger(it)) }
    val fiatAmount: Usdc6? get() = fiatAmountMicroDecimal?.let { Usdc6(BigInteger(it)) }
    val fiatAmountLimit: Usdc6? get() = fiatAmountLimitMicroDecimal?.let { Usdc6(BigInteger(it)) }
    val placeOrderNonce: BigInteger? get() = placeOrderNonceDecimal?.let(::BigInteger)
    val hasUnresolvedPlaceSubmission: Boolean get() = orderIdBig == null && placeOrderTxHash != null

    fun toRequest(fallbackFiatAmount: Usdc6): OfframpRequest {
        val resolvedFiat = fiatAmount ?: fallbackFiatAmount
        return OfframpRequest(
            recipientUpi = recipientUpi,
            usdcAmount = usdcAmount,
            fiatAmount = resolvedFiat,
            payeeName = payeeName,
            currency = currency,
            fiatAmountLimit = fiatAmountLimit ?: resolvedFiat,
            authorizedPayFee = authorizedPayFee,
            authorizedRequiredBalance = authorizedRequiredBalance,
        )
    }
}
