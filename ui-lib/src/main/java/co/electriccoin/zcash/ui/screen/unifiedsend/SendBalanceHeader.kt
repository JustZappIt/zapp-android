package co.electriccoin.zcash.ui.screen.unifiedsend

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.wallet.ExchangeRateState
import co.electriccoin.zcash.ui.common.wallet.toZecFiatRate
import co.electriccoin.zcash.ui.design.component.Spacer
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.theme.balances.LocalBalancesAvailable
import co.electriccoin.zcash.ui.design.util.StringResource.Companion.NUMBER_FORMAT_LOCALE
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.balances.BalanceWidget
import co.electriccoin.zcash.ui.screen.balances.BalanceWidgetState
import co.electriccoin.zcash.ui.screen.exchangerate.widget.createExchangeRateText
import java.text.DecimalFormatSymbols

/** Fiat-first balance header used only by the send screen. */
@Composable
internal fun SendBalanceHeader(state: BalanceWidgetState, modifier: Modifier = Modifier) {
    val rate = state.exchangeRate
    val conversion = (rate as? ExchangeRateState.Data)?.currencyConversion
    val fiatRate = conversion?.toZecFiatRate()
    if (rate is ExchangeRateState.Data && fiatRate != null) {
        val isHidden = LocalBalancesAvailable.current.not()
        val hiddenPlaceholder = stringRes(co.electriccoin.zcash.ui.design.R.string.hide_balance_placeholder)
        val fiatText =
            createExchangeRateText(
                state = rate,
                hiddenBalancePlaceholder = hiddenPlaceholder,
                zatoshi = state.totalBalance,
                isHideBalances = isHidden,
            )
        val zecText = if (isHidden) hiddenPlaceholder.getValue() else stringRes(state.totalBalance).getValue()
        var isFiatPrimary by rememberSaveable(fiatRate.currency.code) { mutableStateOf(true) }
        val toggleDescription = stringResource(R.string.unified_send_toggle_balance_currency)
        Column(
            modifier =
                modifier
                    .semantics { contentDescription = toggleDescription }
                    .clickable(role = Role.Button) { isFiatPrimary = !isFiatPrimary },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PrimaryBalanceText(if (isFiatPrimary) fiatText else zecText)
            Spacer(2.dp)
            SecondaryBalanceText(if (isFiatPrimary) zecText else fiatText)
        }
    } else {
        BalanceWidget(
            modifier = modifier,
            state = state.copy(onAddZec = null, exchangeRate = null),
        )
    }
}

@Composable
private fun PrimaryBalanceText(text: String) {
    val decimalSeparator = DecimalFormatSymbols(NUMBER_FORMAT_LOCALE).decimalSeparator
    val separatorIndex = text.indexOf(decimalSeparator)
    val primary = ZappTheme.typography.balanceDisplay.copy(color = ZappTheme.colors.text)
    val secondary = ZappTheme.typography.balanceFraction.copy(color = ZappTheme.colors.textMuted)
    BasicText(
        text =
            buildAnnotatedString {
                if (shouldDeemphasizeFraction(text, decimalSeparator)) {
                    withStyle(primary.toSpanStyle()) { append(text.take(separatorIndex)) }
                    withStyle(secondary.toSpanStyle()) { append(text.substring(separatorIndex)) }
                } else {
                    withStyle(primary.toSpanStyle()) { append(text) }
                }
            },
        style = primary,
    )
}

internal fun shouldDeemphasizeFraction(text: String, decimalSeparator: Char): Boolean {
    val separatorIndex = text.indexOf(decimalSeparator)
    return separatorIndex >= 0 && text.take(separatorIndex).any { character -> character in '1'..'9' }
}

@Composable
private fun SecondaryBalanceText(text: String) {
    BasicText(
        text = text,
        style = ZappTheme.typography.body.copy(color = ZappTheme.colors.textMuted),
    )
}
