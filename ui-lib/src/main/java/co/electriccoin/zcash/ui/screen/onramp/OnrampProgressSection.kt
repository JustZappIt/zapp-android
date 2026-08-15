package co.electriccoin.zcash.ui.screen.onramp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.zapp.ZappBorderedCard
import co.electriccoin.zcash.ui.design.component.zapp.ZappButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappButtonVariant
import co.electriccoin.zcash.ui.design.component.zapp.ZappStep
import co.electriccoin.zcash.ui.design.component.zapp.ZappStepList
import co.electriccoin.zcash.ui.design.component.zapp.ZappStepStatus
import co.electriccoin.zcash.ui.design.component.zapp.ZappSuccessHeader
import co.electriccoin.zcash.ui.design.component.zapp.ZappSummaryRow
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.stringRes
import xyz.justzappit.offramp.onramp.OnrampDestination
import xyz.justzappit.offramp.onramp.OnrampStatus
import xyz.justzappit.offramp.onramp.OnrampZecDeliveryStatus

@Composable
internal fun ProgressContent(state: OnrampState) {
    Column(verticalArrangement = Arrangement.spacedBy(GAP_LG.dp)) {
        BasicText(
            text = stringResource(R.string.onramp_progress_title),
            style = ZappTheme.typography.display.copy(color = ZappTheme.colors.text),
        )
        BasicText(
            text = progressSubtitle(state).getValue(),
            style = ZappTheme.typography.body.copy(color = ZappTheme.colors.textMuted),
        )
        OrderSummaryCard(state)
        ZappStepList(onrampSteps(state.progress, state.delivery, state.destination))
        if (state.isSettledAgainstUser || state.isDeliveryFailed) {
            FailureCard(state)
        } else {
            ErrorText(state)
        }
    }
}

@Composable
internal fun CompletionContent(state: OnrampState) {
    Column(verticalArrangement = Arrangement.spacedBy(GAP_LG.dp)) {
        ZappSuccessHeader(
            title = completionTitle(state),
            subtitle = completionSubtitle(state),
        )
        ZappBorderedCard(verticalArrangement = Arrangement.spacedBy(GAP_SM.dp)) {
            ZappSummaryRow(stringResource(R.string.onramp_received_label), receivedAmount(state))
            state.fiatPaid?.let {
                ZappSummaryRow(stringResource(R.string.onramp_fiat_paid_label), "${state.currencySymbol}$it")
            }
            state.orderId?.let { ZappSummaryRow(stringResource(R.string.onramp_order_id_label), it) }
        }
        state.transactionExplorerUrl?.let { url ->
            val uriHandler = LocalUriHandler.current
            ZappButton(
                text = stringResource(R.string.onramp_view_transaction),
                modifier = Modifier.fillMaxWidth(),
                variant = ZappButtonVariant.Ghost,
                onClick = { uriHandler.openUri(url) },
            )
        }
        when {
            state.mode == OnrampMode.REFUNDED_TO_BASE -> {
                Notice(stringResource(R.string.onramp_refunded_notice))
                ZappButton(
                    text = stringResource(R.string.onramp_try_conversion_again),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = state.onDeliveryAction,
                )
            }

            state.destination == OnrampDestination.BASE -> {
                Notice(stringResource(R.string.onramp_convert_later))
            }
        }
    }
}

/** What the order is worth and where it is, so the stepper is not the only thing on screen. */
@Composable
private fun OrderSummaryCard(state: OnrampState) {
    ZappBorderedCard(verticalArrangement = Arrangement.spacedBy(GAP_SM.dp)) {
        state.quotedFiat?.let {
            ZappSummaryRow(stringResource(R.string.onramp_you_pay_label), "${state.currencySymbol}$it")
        }
        state.quotedNetUsdc?.let {
            ZappSummaryRow(stringResource(R.string.onramp_you_receive_label), "$it USDC")
        }
        state.orderId?.let { ZappSummaryRow(stringResource(R.string.onramp_order_id_label), it) }
    }
}

/** Terminal failure, in the offramp's red-bordered shape rather than as loose red body text. */
@Composable
private fun FailureCard(state: OnrampState) {
    val c = ZappTheme.colors
    val t = ZappTheme.typography
    ZappBorderedCard(borderColor = c.danger) {
        BasicText(
            text = failureHeader(state).getValue(),
            style = t.button.copy(color = c.danger, fontWeight = FontWeight.SemiBold),
        )
        state.error?.let { reason ->
            Spacer(modifier = Modifier.height(GAP_SM.dp))
            BasicText(text = reason.getValue(), style = t.body.copy(color = c.text))
        }
    }
}

private fun failureHeader(state: OnrampState): StringResource =
    when {
        state.progress is OnrampStatus.Cancelled -> stringRes(R.string.onramp_cancelled_header)
        state.isDeliveryFailed -> stringRes(R.string.onramp_conversion_attention_title)
        else -> stringRes(R.string.onramp_failure_header)
    }

private fun progressSubtitle(state: OnrampState): StringResource =
    when {
        state.mode == OnrampMode.CONVERTING_TO_ZEC -> stringRes(R.string.onramp_converting_subtitle)
        state.isDeliveryFailed -> deliveryFailureMessage(state)
        state.isSettledAgainstUser -> stringRes(R.string.onramp_progress_subtitle_settled)
        state.progress is OnrampStatus.AwaitingMerchant -> stringRes(R.string.onramp_progress_subtitle_matching)
        else -> stringRes(R.string.onramp_progress_subtitle_working)
    }

internal fun onrampSteps(
    status: OnrampStatus?,
    delivery: OnrampZecDeliveryStatus?,
    destination: OnrampDestination,
): List<ZappStep> =
    mapOnrampProgress(status, delivery, destination).map {
        ZappStep(label = it.step.label(), status = it.state.toStepStatus())
    }

private fun OnrampVisibleStep.label(): StringResource =
    when (this) {
        OnrampVisibleStep.ORDER_PLACED -> stringRes(R.string.onramp_step_order_placed)
        OnrampVisibleStep.MERCHANT_MATCHED -> stringRes(R.string.onramp_step_merchant_matched)
        OnrampVisibleStep.PAY_MERCHANT -> stringRes(R.string.onramp_step_pay_merchant)
        OnrampVisibleStep.PAYMENT_CONFIRMED -> stringRes(R.string.onramp_step_payment_confirmed)
        OnrampVisibleStep.RECEIVING_USDC -> stringRes(R.string.onramp_step_receiving)
        OnrampVisibleStep.USDC_RECEIVED -> stringRes(R.string.onramp_step_received)
        OnrampVisibleStep.CONVERTING_TO_ZEC -> stringRes(R.string.onramp_step_converting_to_zec)
        OnrampVisibleStep.ZEC_RECEIVED -> stringRes(R.string.onramp_step_zec_received)
    }

private fun OnrampStepState.toStepStatus(): ZappStepStatus =
    when (this) {
        OnrampStepState.COMPLETED -> ZappStepStatus.Completed
        OnrampStepState.IN_PROGRESS -> ZappStepStatus.InProgress
        OnrampStepState.FAILED -> ZappStepStatus.Failed
        OnrampStepState.PENDING -> ZappStepStatus.Pending
    }

private const val GAP_SM = 6
private const val GAP_LG = 20
