package co.electriccoin.zcash.ui.screen.onramp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.zapp.ZappCompactButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappOfframpHeroAmountField
import co.electriccoin.zcash.ui.design.component.zapp.ZappSegment
import co.electriccoin.zcash.ui.design.component.zapp.ZappSegmentedSelector
import co.electriccoin.zcash.ui.design.component.zapp.ZappSummaryRow
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.util.getValue
import xyz.justzappit.offramp.onramp.OnrampDestination

@Composable
internal fun AmountContent(state: OnrampState) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        IntroCopy(state.destination)
        BasicText(
            text = stringResource(R.string.onramp_amount_label),
            style = ZappTheme.typography.eyebrow.copy(color = ZappTheme.colors.textMuted),
        )
        ZappOfframpHeroAmountField(
            symbol = state.currencySymbol,
            state = state.amountInput,
            secondaryText = null,
            trailingText =
                "${stringResource(R.string.onramp_base_balance_label)}\n" +
                    stringResource(
                        R.string.onramp_base_balance_amount,
                        state.baseBalance ?: stringResource(R.string.onramp_base_balance_unavailable),
                    ),
        )
        if (state.isZecDestinationEnabled) {
            DestinationSelector(state)
        }
        if (state.minFiat != null && state.maxFiat != null) {
            ZappSummaryRow(
                stringResource(R.string.onramp_limits_label),
                "${state.currencySymbol}${state.minFiat} – ${state.currencySymbol}${state.maxFiat}",
            )
        }
        state.dailyLimit?.let {
            ZappSummaryRow(stringResource(R.string.onramp_daily_limit_label), "${state.currencySymbol}$it")
        }
        ZappSummaryRow(stringResource(R.string.onramp_payment_rail_label), state.paymentRail.getValue())
        Notice(stringResource(R.string.onramp_quote_disclaimer))
        if (state.isBaseRefundSupported) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                ZappCompactButton(
                    text =
                        if (state.isSendingBaseBalanceToZec) {
                            stringResource(R.string.onramp_send_to_zec_in_progress)
                        } else {
                            stringResource(R.string.onramp_send_to_zec)
                        },
                    onClick = state.onSendBaseBalanceToZec,
                    enabled =
                        state.canSendBaseBalanceToZec &&
                            !state.isRequestingQuote &&
                            !state.isSendingBaseBalanceToZec,
                )
            }
        }
        if (state.isSendingBaseBalanceToZec) {
            BasicText(
                text = stringResource(R.string.onramp_send_to_zec_keep_open),
                style = ZappTheme.typography.caption.copy(color = ZappTheme.colors.textMuted),
            )
        }
        state.sendBaseBalanceSuccess?.let { success ->
            BasicText(
                text = success.getValue(),
                style = ZappTheme.typography.caption.copy(color = ZappTheme.colors.success),
            )
        }
        state.sendBaseBalanceError?.let { error ->
            BasicText(
                text = error.getValue(),
                style = ZappTheme.typography.caption.copy(color = ZappTheme.colors.danger),
            )
        }
        ErrorText(state)
    }
}

@Composable
private fun DestinationSelector(state: OnrampState) {
    BasicText(
        text = stringResource(R.string.onramp_destination_label),
        style = ZappTheme.typography.eyebrow.copy(color = ZappTheme.colors.textMuted),
    )
    ZappSegmentedSelector(
        segments =
            listOf(
                ZappSegment(
                    label = stringResource(R.string.onramp_destination_zcash),
                    icon = co.electriccoin.zcash.ui.design.R.drawable.ic_token_zec,
                ),
                ZappSegment(
                    label = stringResource(R.string.onramp_destination_base),
                    icon = co.electriccoin.zcash.ui.design.R.drawable.ic_token_usdc,
                ),
            ),
        selectedIndex = if (state.destination == OnrampDestination.ZCASH) 0 else 1,
        onSelect = { index ->
            state.onDestinationSelected(
                if (index == 0) OnrampDestination.ZCASH else OnrampDestination.BASE,
            )
        },
    )
}

/** Everything here is the service's quote, not what the user typed: it quantises the amount. */
@Composable
internal fun ConfirmationContent(state: OnrampState) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        BasicText(
            text = stringResource(R.string.onramp_confirm_title),
            style = ZappTheme.typography.sectionTitle.copy(color = ZappTheme.colors.text),
        )
        ZappSummaryRow(
            stringResource(R.string.onramp_you_pay_label),
            state.quotedFiat?.let { "${state.currencySymbol}$it" } ?: "—",
        )
        ZappSummaryRow(
            stringResource(R.string.onramp_you_receive_label),
            if (state.destination == OnrampDestination.ZCASH) {
                state.estimatedZec?.let { "≈ $it ZEC" }
                    ?: stringResource(R.string.onramp_zec_estimate_loading)
            } else {
                state.quotedNetUsdc?.plus(" USDC") ?: "—"
            },
        )
        if (state.destination == OnrampDestination.ZCASH) {
            ZappSummaryRow(
                stringResource(R.string.onramp_estimated_value_label),
                state.estimatedZecValue ?: "—",
            )
            ZappSummaryRow(
                stringResource(R.string.onramp_estimated_conversion_cost_label),
                state.estimatedConversionCost ?: "—",
            )
        }
        ZappSummaryRow(stringResource(R.string.onramp_fee_label), state.quotedFee?.plus(" USDC") ?: "—")
        ZappSummaryRow(
            stringResource(R.string.onramp_rate_label),
            state.quotedRate?.let { "1 USDC ≈ $it ${state.currencyCode}" } ?: "—",
        )
        state.quoteSecondsRemaining?.let {
            ZappSummaryRow(stringResource(R.string.onramp_quote_expires_in_label), "${it}s")
        }
        ZappSummaryRow(stringResource(R.string.onramp_payment_rail_label), state.paymentRail.getValue())
        if (state.destination == OnrampDestination.BASE) {
            Notice(stringResource(R.string.onramp_quote_refresh_notice))
        }
        ErrorText(state)
    }
}
