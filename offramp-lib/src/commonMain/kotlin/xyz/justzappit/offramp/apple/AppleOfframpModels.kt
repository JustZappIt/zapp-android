// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.apple

data class ApplePaymentCorridor(
    val currencyCode: String,
    val countryName: String,
    val paymentRail: String,
    val flag: String,
    val symbol: String,
    val precision: Int,
)

data class ApplePaymentQrResult(
    val isValid: Boolean,
    val paymentAddress: String? = null,
    val fiatAmount: String? = null,
    val errorCode: String? = null,
)

data class AppleOfframpPaymentDetails(
    val rawPayload: String,
    val paymentAddress: String,
    val fiatAmount: String? = null,
)

/**
 * Host callback used only after a merchant accepts the on-chain order. Keeping QR collection
 * behind this suspended boundary prevents Apple clients from accidentally becoming scan-first.
 */
interface AppleOfframpPaymentDetailsProvider {
    @Throws(Exception::class)
    suspend fun requestPaymentDetails(
        orderId: String,
        currencyCode: String,
        fiatAmount: String,
    ): AppleOfframpPaymentDetails
}

data class AppleOfframpQuote(
    val currencyCode: String,
    val fiatAmount: String,
    val usdcMicros: String,
    val usdcDisplay: String,
    val sellRate: String,
    val fixedFeeMicros: String,
    val fixedFeeDisplay: String,
    val requiredBalanceMicros: String,
    val baseBalanceMicros: String,
    val baseBalanceDisplay: String,
    val shortfallMicros: String,
    val shortfallDisplay: String,
    val canPayFromBase: Boolean,
    val canBridgeToBase: Boolean,
)

data class AppleOfframpAccountSummary(
    val address: String,
    val balanceMicros: String?,
    val balanceDisplay: String?,
    val explorerUrl: String,
    val canBridgeToBase: Boolean,
    val canRefundToZec: Boolean,
)

data class AppleOfframpStatus(
    val kind: String,
    val step: String,
    val title: String,
    val detail: String? = null,
    val orderId: String? = null,
    val txHash: String? = null,
    val bridgeDepositAddress: String? = null,
    val isTerminal: Boolean = false,
    val isSuccess: Boolean = false,
)

data class AppleOfframpHistoryItem(
    val orderId: String,
    val status: String,
    val orderType: String,
    val currencyCode: String,
    val usdcMicros: String,
    val fiatMicros: String,
    val placedAtEpochSeconds: Long?,
    val completedAtEpochSeconds: Long?,
    val cancelledAtEpochSeconds: Long?,
    val paymentAddress: String?,
    val merchantAddress: String?,
    val fixedFeeMicros: String?,
)
