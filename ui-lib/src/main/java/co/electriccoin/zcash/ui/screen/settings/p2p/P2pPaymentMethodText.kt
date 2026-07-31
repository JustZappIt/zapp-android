package co.electriccoin.zcash.ui.screen.settings.p2p

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import co.electriccoin.zcash.ui.R

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

@Composable
internal fun P2pPaymentMethod.selectedSubtitle() =
    stringResource(
        R.string.settings_p2p_payment_method_selected_subtitle,
        title(),
        subtitle(),
    )
