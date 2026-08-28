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

/**
 * The Scan & Pay rails offered in settings.
 *
 * [available] is a merchant-liquidity switch, not a build flag: a corridor is configured on the
 * Diamond and fully implemented here long before anyone is on the other side of it. An order into
 * an empty circle is refused locally — the orchestrator selects a circle before it funds anything,
 * so no gas is spent — but the user reaches a dead end, so the corridor is not offered at all.
 *
 * It is deliberately coarse. Assignability is per amount, not per corridor, so this flag only
 * carries corridors that serve *nothing*; an amount-level gap is caught at quote time by the probe
 * in `UpiOfframpVM`, which asks the chain rather than trusting a constant. Re-measure with
 * `bun scripts/circles.ts <operator> pay` in p2p-onramp-operator before changing one.
 */
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

    QR_SIMPLE(available = true, currency = CurrencyCode.Bob),
    TRANSFERMOVIL(available = true, currency = CurrencyCode.Cup),
    DEUNA(available = true, currency = CurrencyCode.Ecu),

    // Peru's circle 13 assigns no merchant for PAY at any size. Flip to `true` once it staffs up;
    // amount-level gaps are caught at quote time instead, by the probe in UpiOfframpVM.
    YAPE_PLIN(available = false, currency = CurrencyCode.Pen),
    QR_PH(available = true, currency = CurrencyCode.Php),
    ;

    companion object {
        fun fromCurrency(currency: CurrencyCode): P2pPaymentMethod =
            entries.firstOrNull { it.currency == currency } ?: UPI
    }
}
