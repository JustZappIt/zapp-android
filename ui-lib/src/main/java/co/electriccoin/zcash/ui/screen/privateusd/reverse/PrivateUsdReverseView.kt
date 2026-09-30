// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd.reverse

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.security.PinVerifyOverlay
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldState
import co.electriccoin.zcash.ui.design.component.zapp.ZappButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappButtonVariant
import co.electriccoin.zcash.ui.design.component.zapp.ZappSettlementLedger
import co.electriccoin.zcash.ui.design.component.zapp.ZappSettlementLedgerRow
import co.electriccoin.zcash.ui.design.component.zapp.ZappStep
import co.electriccoin.zcash.ui.design.component.zapp.ZappStepStatus
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.chat.common.rememberNotificationPermissionRequester
import co.electriccoin.zcash.ui.screen.privateusd.PrivateUsdInfo
import co.electriccoin.zcash.ui.screen.privateusd.PrivateUsdLayout
import co.electriccoin.zcash.ui.screen.privateusd.PrivateUsdReviewHeader
import co.electriccoin.zcash.ui.screen.privateusd.PrivateUsdScaffold
import co.electriccoin.zcash.ui.screen.privateusd.convert.PrivateUsdConversionDirection
import co.electriccoin.zcash.ui.screen.privateusd.convert.PrivateUsdConvertAmountView
import co.electriccoin.zcash.ui.screen.privateusd.progress.PrivateUsdProgressState
import co.electriccoin.zcash.ui.screen.privateusd.progress.PrivateUsdProgressView
import java.math.BigDecimal

@Composable
internal fun PrivateUsdReverseView(
    state: PrivateUsdReverseState,
    onSwitchDirection: () -> Unit = {},
) {
    // Asked before paying: settling needs the user back in time, and a notification is what brings them.
    val askThenReview =
        rememberNotificationPermissionRequester(R.string.convert_notifications_off, toastsOnce = true) {
            if (state.primary.isEnabled) state.primary.onClick()
        }
    val progress = state.progress
    if (progress != null) {
        PrivateUsdProgressView(progress)
    } else {
        PrivateUsdScaffold(
            title = stringResource(R.string.convert_title),
            layout = PrivateUsdLayout.AMOUNT_ENTRY,
            info = state.info,
            onBack = state.onBack,
            isBackEnabled = state.isBackEnabled,
            primaryButton = if (state.review == null) state.primary.copy(onClick = askThenReview) else state.primary,
            error = state.error,
        ) {
            val review = state.review
            if (review != null) {
                Review(review)
            } else {
                Amount(state, onSwitchDirection.takeIf { state.canSwitchDirection })
                state.rescue?.let { RescueButton(it) }
            }
        }
    }
    state.pinVerify?.let { PinVerifyOverlay(state = it) }
}

@Composable
private fun Amount(
    state: PrivateUsdReverseState,
    onSwitchDirection: (() -> Unit)?,
) {
    PrivateUsdConvertAmountView(
        direction = PrivateUsdConversionDirection.USD_TO_ZEC,
        amount = state.amount,
        note = state.amountNote,
        isInvalid = state.isAmountInvalid,
        estimate = state.receiveEstimate,
        currencySymbol = state.currencySymbol,
        zecAvailable = state.zecAvailable,
        usdAvailable = state.usdAvailable,
        onMax = state.onMax,
        onSwitchDirection = onSwitchDirection,
    )
}

@Composable
private fun Review(review: PrivateUsdReverseReviewState) {
    PrivateUsdReviewHeader(pay = review.debit.getValue(), receive = review.receive.getValue())
    ZappSettlementLedger(
        rows =
            listOf(
                ZappSettlementLedgerRow(stringResource(R.string.reverse_escrow), review.escrow.getValue()),
                ZappSettlementLedgerRow(stringResource(R.string.reverse_railgun_fee), review.railgunFee.getValue()),
                ZappSettlementLedgerRow(
                    stringResource(R.string.reverse_broadcaster_fee),
                    review.broadcasterFee?.getValue() ?: stringResource(R.string.reverse_gas_account),
                ),
                ZappSettlementLedgerRow(stringResource(R.string.reverse_total), review.debit.getValue()),
                ZappSettlementLedgerRow(stringResource(R.string.reverse_receive_estimate), review.receive.getValue()),
            ),
    )
}

@Composable
private fun RescueButton(button: ButtonState) {
    ZappButton(
        text = button.text.getValue(),
        enabled = button.isEnabled,
        variant = ZappButtonVariant.Secondary,
        onClick = button.onClick,
    )
}

@PreviewScreens
@Composable
private fun AmountPreview() =
    ZcashTheme {
        PrivateUsdReverseView(state = previewState())
    }

@PreviewScreens
@Composable
private fun ReviewPreview() =
    ZcashTheme {
        PrivateUsdReverseView(
            state =
                previewState().copy(
                    review =
                        PrivateUsdReverseReviewState(
                            debit = stringRes("₹837.10"),
                            escrow = stringRes("₹835.00"),
                            railgunFee = stringRes("₹2.10"),
                            broadcasterFee = null,
                            receive = stringRes("0.3176 ZEC"),
                        ),
                    primary = ButtonState(stringRes("Convert")),
                ),
        )
    }

@PreviewScreens
@Composable
private fun ProgressPreview() =
    ZcashTheme {
        PrivateUsdReverseView(
            state =
                previewState().copy(
                    progress =
                        PrivateUsdProgressState(
                            amounts = stringRes("₹837.10 for about 0.3176 ZEC after fees"),
                            result = null,
                            steps =
                                listOf(
                                    ZappStep(stringRes("Preparing your receiving account"), ZappStepStatus.Completed),
                                    ZappStep(stringRes("Sending Private USD to escrow"), ZappStepStatus.Completed),
                                    ZappStep(stringRes("Waiting for confirmed ZEC"), ZappStepStatus.Completed),
                                    ZappStep(stringRes("Authorize settlement"), ZappStepStatus.InProgress),
                                ),
                            note = null,
                            problem = null,
                            error = stringRes("Unable to complete this step."),
                            callOff = ButtonState(stringRes("Cancel")),
                            showsBackgroundNote = true,
                            primaryButton = ButtonState(stringRes("Authorize settlement")),
                            info = PrivateUsdInfo(title = stringRes("Private USD → ZEC")),
                            onBack = {},
                        ),
                ),
        )
    }

private fun previewState() =
    PrivateUsdReverseState(
        amount = NumberTextFieldState(NumberTextFieldInnerState.fromAmount(BigDecimal("835"))) {},
        isAmountInvalid = false,
        amountNote = stringRes("₹9.19 to ₹1,669.12"),
        currencySymbol = "₹",
        usdAvailable = stringRes("₹1,200.00"),
        zecAvailable = stringRes("2.51 ZEC"),
        receiveEstimate = NumberTextFieldInnerState.fromAmount(BigDecimal("0.3176")),
        isQuoting = false,
        onMax = {},
        canSwitchDirection = true,
        review = null,
        progress = null,
        rescue = null,
        error = null,
        info = PrivateUsdInfo(title = stringRes("Private USD → ZEC")),
        primary = ButtonState(stringRes("Review")),
        isBackEnabled = true,
        pinVerify = null,
        onBack = {},
    )
