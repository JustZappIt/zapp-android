package co.electriccoin.zcash.ui.screen.onramp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.QrState
import co.electriccoin.zcash.ui.design.component.ZashiQr
import co.electriccoin.zcash.ui.design.component.zapp.ZappBorderedCard
import co.electriccoin.zcash.ui.design.component.zapp.ZappButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappButtonVariant
import co.electriccoin.zcash.ui.design.component.zapp.ZappStepList
import co.electriccoin.zcash.ui.design.component.zapp.ZappSummaryRow
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.util.stringRes
import xyz.justzappit.offramp.onramp.OnrampPaymentInstruction

@Composable
internal fun PaymentContent(state: OnrampState) {
    val instruction = state.paymentInstruction ?: return
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // The one screen the user waits on longest was the one screen without the shared spine.
        ZappStepList(onrampSteps(state.progress))
        BasicText(
            text = stringResource(R.string.onramp_pay_merchant_title),
            style =
                ZappTheme.typography.sectionTitle
                    .copy(color = ZappTheme.colors.text, fontWeight = FontWeight.Black),
        )
        BasicText(
            text = stringResource(R.string.onramp_pay_merchant_body),
            style = ZappTheme.typography.body.copy(color = ZappTheme.colors.textMuted),
        )
        // One block at the offramp's row rhythm: what to send, where, and how long it stays valid
        // are read together, so they sit together rather than drifting apart at screen spacing.
        ZappBorderedCard(verticalArrangement = Arrangement.spacedBy(ROW_GAP.dp)) {
            state.paymentAmount?.let {
                ZappSummaryRow(stringResource(R.string.onramp_exact_fiat_amount), "${state.currencySymbol}$it")
            }
            InstructionRows(instruction)
            state.paymentSecondsRemaining?.let {
                ZappSummaryRow(stringResource(R.string.onramp_expires_in_label), formatCountdown(it))
            }
        }
        // Rendered exactly as the service sent it. Its tr={orderId} is what the merchant
        // reconciles the transfer against and its am= is what the order settles for, so a payload
        // rebuilt here would be paid against the wrong order or for the wrong amount.
        val qrPayload = state.qrPayload?.takeIf { state.isPayable }
        qrPayload?.let { payload ->
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                ZashiQr(
                    state =
                        QrState(
                            qrData = payload,
                            contentDescription = stringRes(R.string.onramp_qr_content_description),
                        ),
                    qrSize = 220.dp,
                    contentPadding = PaddingValues(0.dp),
                )
            }
        }
        // Deliberately no "open my payment app" action. NPCI circular OC-76A disallows intent
        // initiation modes 04 and 05 for person-to-person payees, and every merchant here is a
        // person, so a payment app opens, prefills, takes the user's PIN and only then declines.
        // These three routes are the ones the circular leaves open, so the screen names them.
        qrPayload?.let {
            BasicText(
                text = stringResource(R.string.onramp_pay_manually_hint),
                style = ZappTheme.typography.body.copy(color = ZappTheme.colors.textMuted),
            )
        }
        ZappButton(
            text = stringResource(R.string.onramp_copy_payment_details),
            modifier = Modifier.fillMaxWidth(),
            // Primary only when it is the sole way to capture the payee. With a QR above it,
            // scanning is the path that works and copy is a fallback, so it must not compete with
            // the dock's "I have paid" for the one accent the design system allows.
            variant = if (qrPayload == null) ZappButtonVariant.Primary else ZappButtonVariant.Ghost,
            onClick = state.onCopyPaymentAddress,
        )
        Notice(
            stringResource(
                when {
                    state.isPaymentWindowClosed -> R.string.onramp_payment_window_closed
                    state.isPaymentAmountUntrusted -> R.string.onramp_error_amount_mismatch
                    else -> R.string.onramp_paid_warning
                },
            ),
        )
        ErrorText(state)
    }
}

@Composable
private fun InstructionRows(instruction: OnrampPaymentInstruction) {
    when (instruction) {
        is OnrampPaymentInstruction.Upi -> {
            ZappSummaryRow(stringResource(R.string.onramp_payment_address_label), instruction.address)
        }

        is OnrampPaymentInstruction.Plain -> {
            ZappSummaryRow(stringResource(R.string.onramp_payment_address_label), instruction.address)
        }

        is OnrampPaymentInstruction.Fields -> {
            instruction.fields.forEach { ZappSummaryRow(it.label, it.value) }
        }

        is OnrampPaymentInstruction.Qr -> {
            Unit
        }
    }
}

private fun formatCountdown(totalSeconds: Long): String {
    val minutes = totalSeconds / SECONDS_PER_MINUTE
    val seconds = totalSeconds % SECONDS_PER_MINUTE
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

private const val ROW_GAP = 6
private const val SECONDS_PER_MINUTE = 60
