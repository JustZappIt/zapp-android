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
 * [available] is a merchant-liquidity switch, not a build flag. A corridor is configured on the
 * Diamond and fully implemented here long before anyone is on the other side of it, and an order
 * placed into a circle with no assignable merchant is accepted by the chain and then never filled
 * — it just expires, after the user has paid gas. So a corridor stays unavailable until its circle
 * actually has merchants, and flipping the flag is all that enabling it takes.
 *
 * Checked against `getAssignableMerchantsFromCircle` for orderType PAY, the same read the SDK's
 * router makes before committing an order. Re-check with `bun scripts/circles.ts <operator>` in
 * p2p-onramp-operator before flipping one of these to `true`.
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

    // Bolivia's circle 17 has one registered merchant and none assignable; Peru's circle 13 has
    // none at all. Verified 2026-08-28 at 1/5/20/50/100 USDC. Flip to `true` once they staff up.
    QR_SIMPLE(available = false, currency = CurrencyCode.Bob),
    TRANSFERMOVIL(available = true, currency = CurrencyCode.Cup),
    DEUNA(available = true, currency = CurrencyCode.Ecu),
    YAPE_PLIN(available = false, currency = CurrencyCode.Pen),
    QR_PH(available = true, currency = CurrencyCode.Php),
    ;

    companion object {
        fun fromCurrency(currency: CurrencyCode): P2pPaymentMethod =
            entries.firstOrNull { it.currency == currency } ?: UPI
    }
}
