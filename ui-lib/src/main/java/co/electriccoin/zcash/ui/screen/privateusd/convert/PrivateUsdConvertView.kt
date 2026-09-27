// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd.convert

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldState
import co.electriccoin.zcash.ui.design.component.zapp.ZappFieldBalance
import co.electriccoin.zcash.ui.design.component.zapp.ZappOfframpHeroAmountField
import co.electriccoin.zcash.ui.design.component.zapp.ZappSectionLabel
import co.electriccoin.zcash.ui.design.component.zapp.ZappSettlementLedger
import co.electriccoin.zcash.ui.design.component.zapp.ZappSettlementLedgerRow
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.privateusd.PrivateUsdInfo
import co.electriccoin.zcash.ui.screen.privateusd.PrivateUsdScaffold
import java.math.BigDecimal

@Composable
internal fun PrivateUsdConvertView(state: PrivateUsdConvertState) {
    val askForNotifications = rememberNotificationsRequest()
    PrivateUsdScaffold(
        title = stringResource(R.string.convert_title),
        info = state.info,
        onBack = state.onBack,
        isBackEnabled = !state.primaryButton.isLoading,
        error = state.message,
        primaryButton =
            state.primaryButton.copy(
                onClick = {
                    // Asked before the review, which promises a notification when it's done.
                    if (state.phase == PrivateUsdConvertPhase.AMOUNT) {
                        askForNotifications(state.primaryButton.onClick)
                    } else {
                        state.primaryButton.onClick()
                    }
                },
            ),
    ) {
        when (state.phase) {
            PrivateUsdConvertPhase.AMOUNT -> Amount(state)
            PrivateUsdConvertPhase.REVIEW -> Review(state)
        }
    }
}

@Composable
private fun Amount(state: PrivateUsdConvertState) {
    Column(verticalArrangement = Arrangement.spacedBy(ZappTheme.spacing.md)) {
        ZappSectionLabel(text = stringResource(R.string.convert_amount_label))
        ZappOfframpHeroAmountField(
            symbol = "$",
            state = state.amount,
            secondaryText = state.amountNote.getValue(),
            isError = state.isAmountInvalid,
            balance =
                state.zecAvailable?.let {
                    ZappFieldBalance(stringResource(R.string.convert_zec_available), it.getValue())
                },
        )
    }
    when {
        state.quote != null -> {
            ZappSettlementLedger(
                rows =
                    listOfNotNull(
                        ZappSettlementLedgerRow(stringResource(R.string.convert_you_pay), state.quote.pay.getValue()),
                        state.quote.networkFee?.let {
                            ZappSettlementLedgerRow(stringResource(R.string.convert_network_fee), it.getValue())
                        },
                        ZappSettlementLedgerRow(
                            stringResource(R.string.convert_you_receive),
                            state.quote.receive.getValue(),
                        ),
                        ZappSettlementLedgerRow(stringResource(R.string.convert_fees), state.quote.fees.getValue()),
                    ),
                notice =
                    if (state.isQuoting) {
                        stringResource(R.string.convert_quote_loading)
                    } else {
                        state.quote.refreshesIn.getValue()
                    },
            )
        }

        state.isQuoting -> {
            BasicText(
                text = stringResource(R.string.convert_quote_loading),
                style = ZappTheme.typography.caption.copy(color = ZappTheme.colors.textMuted),
            )
        }
    }
}

@Composable
private fun Review(state: PrivateUsdConvertState) {
    val c = ZappTheme.colors
    BasicText(
        text = stringResource(R.string.convert_review_title),
        style = ZappTheme.typography.sectionTitle.copy(color = c.text),
    )
    state.quote?.let { quote ->
        Column(verticalArrangement = Arrangement.spacedBy(ZappTheme.spacing.xs)) {
            BasicText(text = quote.pay.getValue(), style = ZappTheme.typography.display.copy(color = c.text))
            BasicText(
                text = stringResource(R.string.convert_review_arrow),
                style = ZappTheme.typography.caption.copy(color = c.textMuted),
            )
            BasicText(
                text = quote.receive.getValue(),
                style = ZappTheme.typography.balanceDisplay.copy(color = c.accent),
            )
        }
        ZappSettlementLedger(
            rows =
                listOfNotNull(
                    quote.networkFee?.let {
                        ZappSettlementLedgerRow(stringResource(R.string.convert_network_fee), it.getValue())
                    },
                    ZappSettlementLedgerRow(stringResource(R.string.convert_fees), quote.fees.getValue()),
                ),
            notice = quote.refreshesIn.getValue(),
            noticeIsDanger = state.message != null,
        )
    }
}

/** Asks for the notification permission where Android needs it, then carries on either way. */
@Composable
private fun rememberNotificationsRequest(): (() -> Unit) -> Unit {
    val context = LocalContext.current
    val next = remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { next.value?.invoke() }
    val current = rememberUpdatedState(launcher)
    return remember(context) {
        { onDone ->
            val granted =
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            if (granted) {
                onDone()
            } else {
                next.value = onDone
                current.value.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

@PreviewScreens
@Composable
private fun AmountPreview() =
    ZcashTheme {
        PrivateUsdConvertView(
            state =
                PrivateUsdConvertState(
                    phase = PrivateUsdConvertPhase.AMOUNT,
                    amount = NumberTextFieldState(NumberTextFieldInnerState.fromAmount(BigDecimal.TEN)) {},
                    amountNote = stringRes("≈ ₹835.20"),
                    isAmountInvalid = false,
                    zecAvailable = stringRes("2.51 ZEC"),
                    quote =
                        PrivateUsdQuoteState(
                            pay = stringRes("1.0102 ZEC"),
                            networkFee = stringRes("0.00015 ZEC"),
                            receive = stringRes("$9.89"),
                            fees = stringRes("$0.02 relayer + 0.25% Railgun"),
                            refreshesIn = stringRes("Quote refreshes in 4:12"),
                        ),
                    isQuoting = false,
                    message = null,
                    info = PrivateUsdInfo(title = stringRes("How converting works")),
                    primaryButton = ButtonState(stringRes("Review")),
                    onBack = {},
                ),
        )
    }

@PreviewScreens
@Composable
private fun InvalidAmountPreview() =
    ZcashTheme {
        PrivateUsdConvertView(
            state =
                PrivateUsdConvertState(
                    phase = PrivateUsdConvertPhase.AMOUNT,
                    amount = NumberTextFieldState(NumberTextFieldInnerState.fromAmount(BigDecimal("7.2550001"))) {},
                    amountNote = stringRes("$0.03 to $20"),
                    isAmountInvalid = true,
                    zecAvailable = stringRes("2.51 ZEC"),
                    quote = null,
                    isQuoting = false,
                    message = null,
                    info = PrivateUsdInfo(title = stringRes("How converting works")),
                    primaryButton = ButtonState(stringRes("Review"), isEnabled = false),
                    onBack = {},
                ),
        )
    }
