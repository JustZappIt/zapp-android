// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.apple

data class AppleOnrampLimits(
    val enabled: Boolean,
    val currencyCode: String,
    val minimumFiatMicros: String,
    val maximumFiatMicros: String,
    val dailyFiatMicros: String,
)

data class AppleOnrampQuote(
    val quoteId: String,
    val currencyCode: String,
    val fiatMicros: String,
    val grossUsdcMicros: String,
    val feeUsdcMicros: String,
    val netUsdcMicros: String,
    val buyPriceMicros: String,
    val expiresAtMillis: Long,
)

data class AppleOnrampField(
    val label: String,
    val value: String,
)

data class AppleOnrampStatus(
    val kind: String,
    val phase: String,
    val id: String? = null,
    val orderId: String? = null,
    val failureCode: String? = null,
    val instructionKind: String? = null,
    val instructionAddress: String? = null,
    val instructionPayload: String? = null,
    val instructionFields: List<AppleOnrampField> = emptyList(),
    val fiatMicros: String? = null,
    val netUsdcMicros: String? = null,
    val recipientAddress: String? = null,
    val paidTx: String? = null,
    val expiresAtMillis: Long? = null,
    val isTerminal: Boolean = false,
)

data class AppleOnrampZecEstimate(
    val depositAddress: String,
    val zcashRecipient: String,
    val deadlineMillis: Long,
    val outputZec: String,
    val inputUsd: String,
    val outputUsd: String,
    val costBasisPoints: Int,
)

data class AppleOnrampDeliveryStatus(
    val kind: String,
    val stage: String,
    val inputUsdcMicros: String? = null,
    val outputZec: String? = null,
    val refundedUsdcMicros: String? = null,
    val baseAccount: String? = null,
    val baseTransactionHash: String? = null,
    val fundsLocation: String? = null,
    val retryable: Boolean = false,
    val isTerminal: Boolean = false,
    val isSuccess: Boolean = false,
)

data class AppleOnrampDeliveryCheckpoint(
    val phase: String,
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
    val fundsLocation: String,
)

data class AppleOnrampCheckpoint(
    val id: String,
    val phase: String,
    val orderId: String? = null,
    val destination: String,
    val zecDelivery: AppleOnrampDeliveryCheckpoint? = null,
)

data class AppleOnrampDeviceSignalsRecord(
    val userAgent: String,
    val platform: String,
    val language: String,
    val languages: List<String>,
    val screenWidth: Int,
    val screenHeight: Int,
    val devicePixelRatio: Double,
    val timezone: String,
    val timezoneOffset: Int,
    val cookiesEnabled: Boolean,
    val doNotTrack: String?,
    val online: Boolean,
    val touchSupport: Boolean,
    val maxTouchPoints: Int,
    val vendor: String,
    val appVersion: String,
    val colorDepth: Int,
    val pixelDepth: Int,
    val connectionType: String? = null,
    val deviceMemory: Double? = null,
    val hardwareConcurrency: Int? = null,
    val seonSession: String? = null,
)

/** Raw 1-Click quote echoes. Kotlin validates every field before authorizing a Base transfer. */
data class AppleZecSwapQuote(
    val mode: String,
    val inputUsdcMicros: String,
    val refundAddress: String,
    val recipientAddress: String,
    val destinationAddress: String,
    val depositAddress: String,
    val deadlineMillis: Long,
    val outputZec: String,
    val inputUsd: String,
    val outputUsd: String,
    val slippagePercent: String,
)

/** Raw 1-Click status plus the quote echoes used to bind it to the durable checkpoint. */
data class AppleZecSwapStatus(
    val status: String,
    val mode: String,
    val inputUsdcMicros: String,
    val refundAddress: String,
    val destinationAddress: String,
    val depositAddress: String,
    val outputZec: String,
    val refundedUsdcMicros: String? = null,
)
