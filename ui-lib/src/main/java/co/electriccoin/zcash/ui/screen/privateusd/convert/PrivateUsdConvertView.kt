// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd.convert

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.security.PinVerifyOverlay
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldState
import co.electriccoin.zcash.ui.design.component.zapp.ZappSettlementLedger
import co.electriccoin.zcash.ui.design.component.zapp.ZappSettlementLedgerRow
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.chat.common.rememberNotificationPermissionRequester
import co.electriccoin.zcash.ui.screen.privateusd.PrivateUsdInfo
import co.electriccoin.zcash.ui.screen.privateusd.PrivateUsdLayout
import co.electriccoin.zcash.ui.screen.privateusd.PrivateUsdReviewHeader
import co.electriccoin.zcash.ui.screen.privateusd.PrivateUsdScaffold
import java.math.BigDecimal

@Composable
internal fun PrivateUsdConvertView(
    state: PrivateUsdConvertState,
    onSwitchDirection: () -> Unit = {},
) {
    // Asked before the review, which promises a notification when it's done.
    val askThenReview =
        rememberNotificationPermissionRequester(R.string.convert_notifications_off, toastsOnce = true) {
            if (state.primaryButton.isEnabled) state.primaryButton.onClick()
        }
    PrivateUsdScaffold(
        title = stringResource(R.string.convert_title),
        layout = PrivateUsdLayout.AMOUNT_ENTRY,
        info = state.info,
        onBack = state.onBack,
        isBackEnabled = state.isBackEnabled,
        error = state.message,
        primaryButton =
            when (state.phase) {
                PrivateUsdConvertPhase.AMOUNT -> state.primaryButton.copy(onClick = askThenReview)
                PrivateUsdConvertPhase.REVIEW -> state.primaryButton
            },
    ) {
        when (state.phase) {
            PrivateUsdConvertPhase.AMOUNT -> Amount(state, onSwitchDirection.takeIf { state.canSwitchDirection })
            PrivateUsdConvertPhase.REVIEW -> Review(state)
        }
    }
    state.pinVerify?.let { PinVerifyOverlay(state = it) }
}

@Composable
private fun Amount(
    state: PrivateUsdConvertState,
    onSwitchDirection: (() -> Unit)?,
) {
    PrivateUsdConvertAmountView(
        direction = PrivateUsdConversionDirection.ZEC_TO_USD,
        amount = state.amount,
        note = stringRes(R.string.convert_zec_includes_fee),
        isInvalid = state.isAmountInvalid,
        estimate = state.receiveEstimate,
        currencySymbol = state.currencySymbol,
        zecAvailable = state.zecAvailable,
        usdAvailable = state.usdAvailable,
        isZecBalanceLoading = state.isZecBalanceLoading,
        isUsdBalanceLoading = state.isUsdBalanceLoading,
        usdBalanceError = state.usdBalanceError,
        onRefreshBalance = state.onRefreshBalance,
        onMax = state.onMax,
        onSwitchDirection = onSwitchDirection,
    )
    state.quote?.let { quote ->
        ZappSettlementLedger(
            rows =
                listOf(
                    ZappSettlementLedgerRow(stringResource(R.string.convert_you_pay), quote.pay.getValue()),
                    ZappSettlementLedgerRow(stringResource(R.string.convert_network_fee), quote.networkFee.getValue()),
                    ZappSettlementLedgerRow(stringResource(R.string.convert_you_receive), quote.receive.getValue()),
                    ZappSettlementLedgerRow(
                        stringResource(R.string.convert_fees),
                        quote.fees.getValue(),
                        isSingleLine = true
                    ),
                ),
            notice = quote.expiry.getValue(),
        )
    }
}

@Composable
private fun Review(state: PrivateUsdConvertState) {
    state.quote?.let { quote ->
        PrivateUsdReviewHeader(pay = quote.pay.getValue(), receive = quote.receive.getValue())
        ZappSettlementLedger(
            rows =
                listOf(
                    ZappSettlementLedgerRow(stringResource(R.string.convert_network_fee), quote.networkFee.getValue()),
                    ZappSettlementLedgerRow(
                        stringResource(R.string.convert_fees),
                        quote.fees.getValue(),
                        isSingleLine = true
                    ),
                ),
            notice = quote.expiry.getValue(),
            noticeIsDanger = state.message != null,
        )
    }
}

@PreviewScreens
@Composable
private fun AmountPreview() =
    ZcashTheme {
        PrivateUsdConvertView(state = previewState(PrivateUsdConvertPhase.AMOUNT))
    }

@PreviewScreens
@Composable
private fun ReviewPreview() =
    ZcashTheme {
        PrivateUsdConvertView(state = previewState(PrivateUsdConvertPhase.REVIEW))
    }

private fun previewState(phase: PrivateUsdConvertPhase) =
    PrivateUsdConvertState(
        phase = phase,
        amount = NumberTextFieldState(NumberTextFieldInnerState.fromAmount(BigDecimal("0.2021"))) {},
        isAmountInvalid = false,
        zecAvailable = stringRes("2.51 ZEC"),
        usdAvailable = stringRes("₹835.20"),
        currencySymbol = "₹",
        receiveEstimate = NumberTextFieldInnerState.fromAmount(BigDecimal("81.60")),
        onMax = {},
        quote =
            PrivateUsdQuoteState(
                pay = stringRes("0.2021 ZEC"),
                networkFee = stringRes("0.0001 ZEC"),
                receive = stringRes("₹81.60"),
                fees = stringRes("₹1.67 relayer · 0.25% Railgun"),
                expiry = stringRes("Quote refreshes in 4:12"),
            ),
        isQuoting = false,
        message = null,
        canSwitchDirection = true,
        info = PrivateUsdInfo(title = stringRes("How converting works")),
        primaryButton = ButtonState(stringRes("Review")),
        isBackEnabled = true,
        pinVerify = null,
        onBack = {},
    )
