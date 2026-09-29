// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd.progress

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.zapp.ZappButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappButtonVariant
import co.electriccoin.zcash.ui.design.component.zapp.ZappStep
import co.electriccoin.zcash.ui.design.component.zapp.ZappStepList
import co.electriccoin.zcash.ui.design.component.zapp.ZappStepStatus
import co.electriccoin.zcash.ui.design.component.zapp.ZappSuccessHeader
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.privateusd.PrivateUsdInfo
import co.electriccoin.zcash.ui.screen.privateusd.PrivateUsdScaffold

@Composable
internal fun PrivateUsdProgressView(state: PrivateUsdProgressState) {
    val c = ZappTheme.colors
    PrivateUsdScaffold(
        title = stringResource(R.string.convert_title),
        info = state.info,
        onBack = state.onBack,
        primaryButton = state.primaryButton,
    ) {
        Header(state)
        state.note?.let {
            BasicText(
                text = it.getValue(),
                style = ZappTheme.typography.body.copy(color = c.accentText, fontWeight = FontWeight.Medium),
            )
        }
        state.callOff?.let {
            ZappButton(
                text = it.text.getValue(),
                enabled = it.isEnabled,
                loading = it.isLoading,
                variant = ZappButtonVariant.Ghost,
                modifier = Modifier.fillMaxWidth(),
                onClick = it.onClick,
            )
        }
        if (state.steps.isNotEmpty()) ZappStepList(steps = state.steps)
        if (state.showsBackgroundNote) {
            BasicText(
                text = stringResource(R.string.convert_background_note),
                style = ZappTheme.typography.caption.copy(color = c.textMuted),
            )
        }
    }
}

@Composable
private fun Header(state: PrivateUsdProgressState) {
    val c = ZappTheme.colors
    val result = state.result
    if (result?.isSuccess == true) {
        ZappSuccessHeader(title = result.title, subtitle = result.body)
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(ZappTheme.spacing.sm)) {
            BasicText(
                text = result?.title?.getValue() ?: stringResource(R.string.convert_progress_title),
                style = ZappTheme.typography.sectionTitle.copy(color = c.text),
            )
            result?.let {
                BasicText(text = it.body.getValue(), style = ZappTheme.typography.body.copy(color = c.textMuted))
            }
            state.amounts?.let {
                BasicText(text = it.getValue(), style = ZappTheme.typography.caption.copy(color = c.textMuted))
            }
        }
    }
}

@PreviewScreens
@Composable
private fun UnderWayPreview() =
    ZcashTheme {
        PrivateUsdProgressView(
            state =
                PrivateUsdProgressState(
                    amounts = stringRes("0.2020 ZEC for about $0.98"),
                    result = null,
                    steps =
                        listOf(
                            ZappStep(stringRes("Opening the swap on Ethereum"), ZappStepStatus.Completed),
                            ZappStep(stringRes("Sending your ZEC"), ZappStepStatus.Completed),
                            ZappStep(
                                stringRes("Waiting for confirmations"),
                                ZappStepStatus.InProgress,
                                detailLines = listOf(stringRes("2 of 3 · about 2 min")),
                            ),
                            ZappStep(stringRes("Claiming your dollars"), ZappStepStatus.Pending),
                            ZappStep(stringRes("Arriving in your private balance"), ZappStepStatus.Pending),
                            ZappStep(stringRes("Screening"), ZappStepStatus.Pending),
                        ),
                    note = null,
                    callOff = null,
                    showsBackgroundNote = true,
                    primaryButton = null,
                    info = PrivateUsdInfo(title = stringRes("What's happening")),
                    onBack = {},
                ),
        )
    }

@PreviewScreens
@Composable
private fun RefundedPreview() =
    ZcashTheme {
        PrivateUsdProgressView(
            state =
                PrivateUsdProgressState(
                    amounts = stringRes("0.2020 ZEC for about $0.98"),
                    result =
                        PrivateUsdResultState(
                            title = stringRes("Your ZEC came back"),
                            body = stringRes("The market maker called the swap off, so your ZEC came back."),
                            isSuccess = false,
                        ),
                    steps = emptyList(),
                    note = null,
                    callOff = null,
                    showsBackgroundNote = false,
                    primaryButton = ButtonState(stringRes("Try again")),
                    info = PrivateUsdInfo(title = stringRes("What's happening")),
                    onBack = {},
                ),
        )
    }
