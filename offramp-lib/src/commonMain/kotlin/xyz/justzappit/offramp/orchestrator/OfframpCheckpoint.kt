// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.orchestrator

import kotlinx.serialization.Serializable
import xyz.justzappit.evm.math.BigInteger
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
    val setUpiTxHash: TxHash? = null,
    val recipientUpi: String,
    val usdcAmountMicroDecimal: String,
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
            require(runCatching { BigInteger(orderId!!) }.isSuccess) {
                "OfframpCheckpoint.orderId must be a decimal integer, got '$orderId'"
            }
        }
        require(runCatching { BigInteger(usdcAmountMicroDecimal) }.isSuccess) {
            "OfframpCheckpoint.usdcAmountMicroDecimal must be a decimal integer, got '$usdcAmountMicroDecimal'"
        }
        if (fiatAmountMicroDecimal != null) {
            require(runCatching { BigInteger(fiatAmountMicroDecimal!!) }.isSuccess) {
                "OfframpCheckpoint.fiatAmountMicroDecimal must be a decimal integer, got '$fiatAmountMicroDecimal'"
            }
        }
        if (fiatAmountLimitMicroDecimal != null) {
            require(runCatching { BigInteger(fiatAmountLimitMicroDecimal!!) }.isSuccess) {
                "OfframpCheckpoint.fiatAmountLimitMicroDecimal must be a decimal integer, " +
                    "got '$fiatAmountLimitMicroDecimal'"
            }
        }
    }

    val orderIdBig: BigInteger? get() = orderId?.let(::BigInteger)
    val usdcAmount: Usdc6 get() = Usdc6(BigInteger(usdcAmountMicroDecimal))
    val fiatAmount: Usdc6? get() = fiatAmountMicroDecimal?.let { Usdc6(BigInteger(it)) }
    val fiatAmountLimit: Usdc6? get() = fiatAmountLimitMicroDecimal?.let { Usdc6(BigInteger(it)) }

    fun toRequest(fallbackFiatAmount: Usdc6): OfframpRequest {
        val resolvedFiat = fiatAmount ?: fallbackFiatAmount
        return OfframpRequest(
            recipientUpi = recipientUpi,
            usdcAmount = usdcAmount,
            fiatAmount = resolvedFiat,
            payeeName = payeeName,
            currency = currency,
            fiatAmountLimit = fiatAmountLimit ?: resolvedFiat,
        )
    }
}
