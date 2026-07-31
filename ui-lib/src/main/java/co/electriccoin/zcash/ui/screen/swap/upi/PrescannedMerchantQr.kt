package co.electriccoin.zcash.ui.screen.swap.upi

import co.electriccoin.zcash.ui.screen.swap.UpiOfframpArgs
import java.math.BigDecimal

/**
 * A merchant payment QR already scanned and validated by the home Pay-tab scanner, carried into the
 * pay-merchant flow so it never re-prompts for the QR mid-order. [EMPTY] is the "user opened the
 * offramp normally" case; a present instance prefills the amount (when [fiatAmount] is set) and lets
 * [co.electriccoin.zcash.ui.screen.swap.upi.progress.UpiOfframpProgressVM] build the payment details
 * directly instead of launching the corridor scanner.
 */
data class PrescannedMerchantQr(
    val rawPayload: String? = null,
    val paymentAddress: String? = null,
    val fiatAmount: BigDecimal? = null,
) {
    val isPresent: Boolean get() = rawPayload != null && paymentAddress != null

    companion object {
        val EMPTY = PrescannedMerchantQr()
    }
}

fun UpiOfframpArgs.toPrescannedMerchantQr(): PrescannedMerchantQr =
    if (prescannedPayload != null && prescannedPaymentAddress != null) {
        PrescannedMerchantQr(
            rawPayload = prescannedPayload,
            paymentAddress = prescannedPaymentAddress,
            fiatAmount = prescannedFiatAmount?.let { runCatching { BigDecimal(it) }.getOrNull() },
        )
    } else {
        PrescannedMerchantQr.EMPTY
    }
