package co.electriccoin.zcash.ui.screen.settings.p2p

import co.electriccoin.zcash.ui.common.model.P2pProvider
import co.electriccoin.zcash.ui.common.model.P2pRail
import co.electriccoin.zcash.ui.design.component.ButtonState
import xyz.justzappit.offramp.p2p.CurrencyCode

internal data class P2pPaymentMethodState(
    val baseAddress: String?,
    val isAddressCopied: Boolean,
    val sections: List<P2pPaymentMethodSectionState>,
    val saveButton: ButtonState,
    val onCopyBaseAddress: () -> Unit,
    val onBack: () -> Unit,
)

internal data class P2pPaymentMethodSectionState(
    val provider: P2pProvider,
    val items: List<P2pPaymentMethodItemState>,
)

internal data class P2pPaymentMethodItemState(
    val rail: P2pRail,
    val isSelected: Boolean,
    val isAvailable: Boolean,
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
