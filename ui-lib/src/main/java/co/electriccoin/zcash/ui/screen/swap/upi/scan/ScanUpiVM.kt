package co.electriccoin.zcash.ui.screen.swap.upi.scan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.usecase.NavigateToScanUpiUseCase
import co.electriccoin.zcash.ui.screen.scan.ImageToQrCodeResult
import co.electriccoin.zcash.ui.screen.scan.ScanValidationState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.justzappit.offramp.p2p.CurrencyCode
import xyz.justzappit.offramp.p2p.DynamicPixResolver
import xyz.justzappit.offramp.p2p.PaymentQrError
import xyz.justzappit.offramp.p2p.PaymentQrParseResult
import xyz.justzappit.offramp.p2p.PaymentQrParser

/**
 * Scan VM for the offramp corridors. Mirrors [co.electriccoin.zcash.ui.screen.scan.ScanGenericAddressVM]
 * but routes the scanned payload through [PaymentQrParser] (byte-compatible with `@p2pdotme/sdk`
 * v1.1.7) for the selected [CurrencyCode] before declaring the scan VALID. A merchant QR that doesn't
 * pattern-match the SDK's regex would fail downstream inside `placeOrder`/`setSellOrderUpi`, so we
 * reject early.
 */
internal class ScanUpiVM(
    private val args: ScanUpiArgs,
    private val navigateToScanUpi: NavigateToScanUpiUseCase,
    private val dynamicPixResolver: DynamicPixResolver,
) : ViewModel() {
    private val currency = CurrencyCode.fromCodeOrNull(args.currencyCode) ?: CurrencyCode.Inr

    val state: MutableStateFlow<ScanValidationState> = MutableStateFlow(ScanValidationState.NONE)

    private val mutex = Mutex()
    private var hasBeenScannedSuccessfully = false

    fun onScanned(result: String) =
        viewModelScope.launch {
            mutex.withLock {
                if (hasBeenScannedSuccessfully) return@withLock
                when (val parsed = PaymentQrParser.parse(currency, result, dynamicPixResolver)) {
                    is PaymentQrParseResult.Success -> {
                        hasBeenScannedSuccessfully = true
                        state.update { ScanValidationState.VALID }
                        navigateToScanUpi.onScanned(
                            rawPayload = result,
                            paymentAddress = parsed.parsed.paymentAddress,
                            fiatAmount = parsed.parsed.fiatAmount,
                            args = args,
                        )
                    }

                    is PaymentQrParseResult.Failure -> {
                        Twig.info { "ScanUpiVM: rejecting scanned payload, error=${parsed.error.diagnosticTag()}" }
                        state.update { ScanValidationState.INVALID }
                    }
                }
            }
        }

    fun onImageScanned(result: ImageToQrCodeResult) =
        viewModelScope.launch {
            mutex.withLock {
                if (hasBeenScannedSuccessfully) return@withLock
                when (result) {
                    is ImageToQrCodeResult.SingleCode -> onScanned(result.text)
                    ImageToQrCodeResult.MultipleCodes -> state.update { ScanValidationState.SEVERAL_CODES_FOUND }
                    ImageToQrCodeResult.NoCode -> state.update { ScanValidationState.INVALID_IMAGE }
                }
            }
        }

    fun onBack() = viewModelScope.launch { navigateToScanUpi.onScanCancelled(args) }

    private fun PaymentQrError.diagnosticTag(): String =
        when (this) {
            is PaymentQrError.EmptyQr -> "empty"
            is PaymentQrError.InvalidFormat -> "invalid-format"
            is PaymentQrError.MissingPaymentAddress -> "missing-pa"
            is PaymentQrError.InvalidPaymentAddress -> "invalid-address"
            is PaymentQrError.InvalidChecksum -> "invalid-crc"
            is PaymentQrError.InvalidAmount -> "invalid-amount"
            is PaymentQrError.DynamicFetchFailed -> "dynamic-fetch-failed"
            is PaymentQrError.UnsupportedCurrency -> "unsupported-currency"
        }
}
