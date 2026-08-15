package co.electriccoin.zcash.ui.screen.settings.p2p

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.P2pProvider
import co.electriccoin.zcash.ui.common.model.P2pRail
import xyz.justzappit.offramp.peer.PeerPlatform

@Composable
internal fun P2pRail.title(): String =
    when (this) {
        is P2pRail.ScanAndPay -> P2pPaymentMethod.fromCurrency(currency).title()
        is P2pRail.PeerCashOut -> platform.title()
    }

@Composable
internal fun P2pRail.subtitle(): String =
    when (this) {
        is P2pRail.ScanAndPay -> P2pPaymentMethod.fromCurrency(currency).subtitle()
        is P2pRail.PeerCashOut -> platform.currencySummary()
    }

@Composable
internal fun P2pRail.selectedSubtitle(): String =
    stringResource(R.string.settings_p2p_payment_method_selected_subtitle, title(), subtitle())

@Composable
internal fun P2pProvider.title(): String =
    when (this) {
        P2pProvider.P2P_ME -> stringResource(R.string.settings_p2p_provider_p2pme)
        P2pProvider.PEER -> stringResource(R.string.settings_p2p_provider_peer)
    }

@Composable
private fun PeerPlatform.title(): String =
    when (this) {
        PeerPlatform.REVOLUT -> stringResource(R.string.settings_p2p_rail_revolut)
        PeerPlatform.ZELLE -> stringResource(R.string.settings_p2p_rail_zelle)
        PeerPlatform.CHIME -> stringResource(R.string.settings_p2p_rail_chime)
        PeerPlatform.MONZO -> stringResource(R.string.settings_p2p_rail_monzo)
    }

@Composable
private fun PeerPlatform.currencySummary(): String {
    val shown = defaultCurrencies.joinToString(CURRENCY_SEPARATOR) { it.code }
    val remaining = currencies.size - defaultCurrencies.size
    return if (remaining > 0) {
        stringResource(R.string.settings_p2p_rail_currencies_more, shown, remaining)
    } else {
        shown
    }
}

@Composable
internal fun P2pPaymentMethod.title() =
    when (this) {
        P2pPaymentMethod.UPI -> stringResource(R.string.settings_p2p_payment_method_upi)
        P2pPaymentMethod.PIX -> stringResource(R.string.settings_p2p_payment_method_pix)
        P2pPaymentMethod.QRIS -> stringResource(R.string.settings_p2p_payment_method_qris)
        P2pPaymentMethod.MERCADOPAGO -> stringResource(R.string.settings_p2p_payment_method_mercadopago)
        P2pPaymentMethod.PAGO_MOVIL -> stringResource(R.string.settings_p2p_payment_method_pago_movil)
        P2pPaymentMethod.NIP -> stringResource(R.string.settings_p2p_payment_method_nip)
        P2pPaymentMethod.TRANSFERENCIA -> stringResource(R.string.settings_p2p_payment_method_transferencia)
    }

@Composable
internal fun P2pPaymentMethod.subtitle() =
    when (this) {
        P2pPaymentMethod.UPI -> stringResource(R.string.settings_p2p_payment_method_upi_subtitle)
        P2pPaymentMethod.PIX -> stringResource(R.string.settings_p2p_payment_method_pix_subtitle)
        P2pPaymentMethod.QRIS -> stringResource(R.string.settings_p2p_payment_method_qris_subtitle)
        P2pPaymentMethod.MERCADOPAGO -> stringResource(R.string.settings_p2p_payment_method_mercadopago_subtitle)
        P2pPaymentMethod.PAGO_MOVIL -> stringResource(R.string.settings_p2p_payment_method_pago_movil_subtitle)
        P2pPaymentMethod.NIP -> stringResource(R.string.settings_p2p_payment_method_nip_subtitle)
        P2pPaymentMethod.TRANSFERENCIA -> stringResource(R.string.settings_p2p_payment_method_transferencia_subtitle)
    }

private const val CURRENCY_SEPARATOR = ", "
