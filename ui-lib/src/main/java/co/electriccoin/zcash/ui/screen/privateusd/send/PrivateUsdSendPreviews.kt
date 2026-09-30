// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd.send

import androidx.compose.runtime.Composable
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldState
import co.electriccoin.zcash.ui.design.component.TextFieldState
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.privateusd.PrivateUsdInfo

@PreviewScreens
@Composable
private fun FormPreview() =
    ZcashTheme {
        PrivateUsdSendView(state = previewState(PrivateUsdSendPhase.FORM))
    }

@PreviewScreens
@Composable
private fun ReviewPreview() =
    ZcashTheme {
        PrivateUsdSendView(state = previewState(PrivateUsdSendPhase.REVIEW))
    }

@PreviewScreens
@Composable
private fun SendingPreview() =
    ZcashTheme {
        PrivateUsdSendView(state = previewState(PrivateUsdSendPhase.SENDING))
    }

@PreviewScreens
@Composable
private fun DonePreview() =
    ZcashTheme {
        PrivateUsdSendView(state = previewState(PrivateUsdSendPhase.DONE))
    }

private fun previewState(phase: PrivateUsdSendPhase) =
    PrivateUsdSendState(
        phase = phase,
        mode = PrivateUsdSendMode.WITHDRAW,
        onModeSelect = {},
        assets =
            listOf(
                PrivateUsdSendAssetState("tUSD", isSelected = true) {},
                PrivateUsdSendAssetState("USDC", isSelected = false) {},
            ),
        amount = NumberTextFieldState(NumberTextFieldInnerState()) {},
        currencySymbol = "$",
        amountNote = null,
        isAmountInvalid = false,
        available = stringRes("$12.345678"),
        onMax = {},
        recipient = TextFieldState(value = stringRes("")) {},
        review =
            PrivateUsdSendReviewState(
                token = "tUSD",
                amount = stringRes("$1.00"),
                railgunFee = PrivateUsdSendFee(stringRes("Railgun fee (0.25%)"), stringRes("$0.0025")),
                receives = stringRes("$0.9975"),
                to = "0x1c7f9a756b08753cf8da94d394659134bb8c5539",
            ),
        proofProgress = 0.4f,
        done =
            PrivateUsdSendDoneState(
                body = stringRes("$1.00 went to 0x1c7f9a75…8c5539."),
                onViewTransaction = {},
                note = null,
            ),
        error = null,
        info = PrivateUsdInfo(title = stringRes("Withdrawing makes it public")),
        primaryButton = ButtonState(stringRes("Review"), isEnabled = false),
        onBack = {},
    )
