package co.electriccoin.zcash.ui.screen.scan

import androidx.annotation.StringRes
import co.electriccoin.zcash.ui.R

/**
 * The failure copy [ScanView] shows for each rejected [ScanValidationState]. Defaults to the
 * Zcash-address wording used by the address scanners; the offramp corridors
 * ([co.electriccoin.zcash.ui.screen.swap.upi.scan.ScanUpiScreen]) override it with payment-QR copy
 * so a rejected merchant QR never tells the user their scan was not a valid "Zcash address".
 */
data class ScanFailureMessages(
    @param:StringRes val invalid: Int,
    @param:StringRes val invalidImage: Int,
    @param:StringRes val severalCodes: Int,
) {
    companion object {
        val ZCASH_ADDRESS =
            ScanFailureMessages(
                invalid = R.string.scan_address_validation_failed,
                invalidImage = R.string.scan_invalid_image,
                severalCodes = R.string.scan_several_codes_found,
            )

        val PAYMENT_QR =
            ScanFailureMessages(
                invalid = R.string.offramp_scan_invalid,
                invalidImage = R.string.offramp_scan_invalid_image,
                severalCodes = R.string.offramp_scan_several_codes,
            )

        // Home Pay-tab scanner: accepts Zcash addresses AND local payment QRs, so the rejection copy
        // must cover both rather than claiming only "Zcash address".
        val HOMEPAGE_ADDRESS_OR_PAYMENT =
            ScanFailureMessages(
                invalid = R.string.scan_homepage_validation_failed,
                invalidImage = R.string.scan_homepage_invalid_image,
                severalCodes = R.string.scan_several_codes_found,
            )
    }
}
