package co.electriccoin.zcash.ui.screen.privateusd.convert

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldState
import co.electriccoin.zcash.ui.design.component.zapp.ZappFieldBalance
import co.electriccoin.zcash.ui.design.component.zapp.ZappOfframpHeroAmountField
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.getValue
import java.math.BigDecimal

@Composable
internal fun PrivateUsdConvertAmountView(
    amount: NumberTextFieldState,
    usdAvailable: StringResource?,
    zecAvailable: StringResource?,
    receiveEstimate: BigDecimal?,
    onMax: (() -> Unit)?,
    usdOnTop: Boolean,
    currencySymbol: String,
    note: StringResource?,
    isInvalid: Boolean,
    directionSelector: @Composable () -> Unit,
) {
    AssetAmount(
        isUsd = usdOnTop,
        currencySymbol = currencySymbol,
        amount = amount,
        available = if (usdOnTop) usdAvailable else zecAvailable,
        note = note?.getValue(),
        isInvalid = isInvalid,
        onMax = onMax,
    )
    directionSelector()
    AssetAmount(
        isUsd = !usdOnTop,
        currencySymbol = currencySymbol,
        amount =
            NumberTextFieldState(
                innerState = receiveEstimate?.let(NumberTextFieldInnerState::fromAmount) ?: NumberTextFieldInnerState(),
                isEnabled = false,
                onValueChange = {},
            ),
        available = if (usdOnTop) zecAvailable else usdAvailable,
        note = stringResource(R.string.convert_estimated_amount),
        isInvalid = false,
        onMax = null,
    )
}

@Composable
private fun AssetAmount(
    isUsd: Boolean,
    currencySymbol: String,
    amount: NumberTextFieldState,
    available: StringResource?,
    note: String?,
    isInvalid: Boolean,
    onMax: (() -> Unit)?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(ZappTheme.spacing.md)) {
        AssetLabel(isUsd)
        ZappOfframpHeroAmountField(
            symbol = if (isUsd) currencySymbol else "",
            leadingIcon =
                painterResource(
                    if (isUsd) {
                        co.electriccoin.zcash.ui.design.R.drawable.ic_token_usdc
                    } else {
                        co.electriccoin.zcash.ui.design.R.drawable.ic_token_zec
                    }
                ),
            state = amount,
            secondaryText = note,
            isError = isInvalid,
            balance =
                ZappFieldBalance(
                    label = stringResource(R.string.convert_zec_available),
                    amount = available?.getValue() ?: stringResource(R.string.reverse_balance_loading),
                    onClick = onMax,
                ),
        )
    }
}

@Composable
private fun AssetLabel(isUsd: Boolean) {
    BasicText(
        text = stringResource(if (isUsd) R.string.convert_asset_private_usd else R.string.convert_asset_zec),
        style = ZappTheme.typography.caption.copy(color = ZappTheme.colors.textMuted),
    )
}
