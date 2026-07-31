package co.electriccoin.zcash.ui.screen.swap.upi.progress

import kotlinx.serialization.Serializable
import xyz.justzappit.offramp.p2p.CurrencyCode

@Serializable
data class UpiOfframpProgressArgs(
    val recipientUpi: String = "",
    val usdcAmountMicro: String,
    val fiatAmountMicro: String,
    val fiatAmountLimitMicro: String? = null,
    val currency: CurrencyCode,
    val payeeName: String? = null,
    // A merchant QR pre-scanned on the home tab: when set, the progress VM builds the payment
    // details directly instead of launching the mid-order corridor scanner. Fiat is a plain-decimal
    // string (the QR's fixed amount, null for an open QR).
    val prescannedPayload: String? = null,
    val prescannedPaymentAddress: String? = null,
    val prescannedFiatAmount: String? = null,
)
