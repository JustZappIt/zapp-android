package co.electriccoin.zcash.ui.screen.settings.p2p

import co.electriccoin.zcash.ui.design.component.ButtonState
import xyz.justzappit.offramp.p2p.CurrencyCode

internal data class P2pPaymentMethodState(
    val baseAddress: String?,
    val isAddressCopied: Boolean,
    val items: List<P2pPaymentMethodItemState>,
    val saveButton: ButtonState,
    val onCopyBaseAddress: () -> Unit,
    val onBack: () -> Unit,
)

internal data class P2pPaymentMethodItemState(
    val method: P2pPaymentMethod,
    val isSelected: Boolean,
    val onClick: () -> Unit,
)

internal enum class P2pPaymentMethod(
    val available: Boolean,
    val currency: CurrencyCode,
) {
    UPI(available = true, currency = CurrencyCode.Inr),
    PIX(available = true, currency = CurrencyCode.Brl),
    QRIS(available = true, currency = CurrencyCode.Idr),
    MERCADOPAGO(available = true, currency = CurrencyCode.Ars),
    PAGO_MOVIL(available = true, currency = CurrencyCode.Ven),
    NIP(available = true, currency = CurrencyCode.Ngn),
    TRANSFERENCIA(available = true, currency = CurrencyCode.Cop),
    ;

    companion object {
        fun fromCurrency(currency: CurrencyCode): P2pPaymentMethod =
            entries.firstOrNull { it.currency == currency } ?: UPI
    }
}
