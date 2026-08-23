// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.gift

import androidx.compose.runtime.Composable
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.design.component.NumberTextFieldState
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ProvideZappTheme
import co.electriccoin.zcash.ui.design.util.stringRes

@PreviewScreens
@Composable
private fun GiftCardPreview() =
    ProvideZappTheme {
        GiftCardView(
            state =
                GiftCardState(
                    stage = GiftCardStage.DETAILS,
                    amount = NumberTextFieldState(onValueChange = {}),
                    spendableBalance = stringRes(Zatoshi(0)),
                    message = "",
                    messageGraphemes = 0,
                    expiry = GiftExpiry.NEVER,
                    quote = null,
                    previewAmount = Zatoshi(250_000_000L),
                    fiat = stringRes("$150.00"),
                    link = null,
                    isAuthenticating = false,
                    error = null,
                    pinVerify = null,
                    onAmountChange = {},
                    onMessageChange = {},
                    onExpiryChange = {},
                    onContinue = {},
                    onConfirm = {},
                    onShare = {},
                    onBack = {},
                    onOpenSavedCards = null,
                )
        )
    }
