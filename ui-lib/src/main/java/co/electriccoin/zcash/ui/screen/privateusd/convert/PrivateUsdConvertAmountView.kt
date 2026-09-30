// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

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
import co.electriccoin.zcash.ui.design.component.zapp.ZappFieldBalanceAction
import co.electriccoin.zcash.ui.design.component.zapp.ZappOfframpHeroAmountField
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.getValue

/** What's converted above what it brings, and the toggle between them. */
@Composable
internal fun PrivateUsdConvertAmountView(
    direction: PrivateUsdConversionDirection,
    amount: NumberTextFieldState,
    note: StringResource,
    isInvalid: Boolean,
    estimate: NumberTextFieldInnerState,
    currencySymbol: String,
    zecAvailable: StringResource?,
    usdAvailable: StringResource,
    onMax: (() -> Unit)?,
    onSwitchDirection: (() -> Unit)?,
) {
    fun available(asset: ConvertAsset) =
        when (asset) {
            ConvertAsset.ZEC -> zecAvailable
            ConvertAsset.PRIVATE_USD -> usdAvailable
        }
    AssetAmount(
        asset = direction.from,
        currencySymbol = currencySymbol,
        amount = amount,
        available = available(direction.from),
        note = note.getValue(),
        isInvalid = isInvalid,
        onMax = onMax,
    )
    PrivateUsdDirectionToggle(onClick = onSwitchDirection)
    AssetAmount(
        asset = direction.to,
        currencySymbol = currencySymbol,
        amount = NumberTextFieldState(innerState = estimate, isEnabled = false, onValueChange = {}),
        available = available(direction.to),
        note = stringResource(R.string.convert_estimated_amount),
        isInvalid = false,
        onMax = null,
    )
}

@Composable
private fun AssetAmount(
    asset: ConvertAsset,
    currencySymbol: String,
    amount: NumberTextFieldState,
    available: StringResource?,
    note: String,
    isInvalid: Boolean,
    onMax: (() -> Unit)?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(ZappTheme.spacing.md)) {
        BasicText(
            text = stringResource(asset.label),
            style = ZappTheme.typography.caption.copy(color = ZappTheme.colors.textMuted),
        )
        ZappOfframpHeroAmountField(
            symbol = if (asset == ConvertAsset.PRIVATE_USD) currencySymbol else "",
            leadingIcon = painterResource(asset.icon),
            shrinksLongAmounts = true,
            state = amount,
            secondaryText = note,
            isError = isInvalid,
            balance =
                ZappFieldBalance(
                    label = stringResource(R.string.private_usd_row_available),
                    amount = available?.getValue() ?: stringResource(R.string.private_usd_home_loading),
                    action =
                        onMax?.let {
                            ZappFieldBalanceAction(
                                onClickLabel = stringResource(R.string.private_usd_use_available),
                                onClick = it,
                            )
                        },
                ),
        )
    }
}
