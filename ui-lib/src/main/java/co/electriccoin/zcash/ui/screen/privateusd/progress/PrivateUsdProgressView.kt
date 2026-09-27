// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd.progress

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.zapp.ZappBorderedCard
import co.electriccoin.zcash.ui.design.component.zapp.ZappBottomActionBar
import co.electriccoin.zcash.ui.design.component.zapp.ZappButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappButtonVariant
import co.electriccoin.zcash.ui.design.component.zapp.ZappSectionLabel
import co.electriccoin.zcash.ui.design.component.zapp.ZappStep
import co.electriccoin.zcash.ui.design.component.zapp.ZappStepList
import co.electriccoin.zcash.ui.design.component.zapp.ZappStepStatus
import co.electriccoin.zcash.ui.design.component.zapp.ZappSuccessHeader
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.stringRes

@Composable
internal fun PrivateUsdProgressView(state: PrivateUsdProgressState) {
    val c = ZappTheme.colors
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(c.bg)
                .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout)),
    ) {
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = ZappTheme.spacing.xl, vertical = ZappTheme.spacing.xl),
            verticalArrangement = Arrangement.spacedBy(ZappTheme.spacing.xl2),
        ) {
            Header(state)
            state.problem?.let { Problem(message = it.getValue(), onRetry = state.onRetry) }
            state.note?.let {
                BasicText(
                    text = it.getValue(),
                    style = ZappTheme.typography.body.copy(color = c.accentText, fontWeight = FontWeight.Medium),
                )
            }
            state.callOff?.let {
                ZappButton(
                    text = it.text.getValue(),
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
        ZappBottomActionBar(
            onBack = state.onBack,
            primaryAction =
                state.primaryButton?.let { button ->
                    {
                        ZappButton(
                            text = button.text.getValue(),
                            modifier = Modifier.weight(1f).padding(start = ZappTheme.spacing.lg),
                            onClick = button.onClick,
                        )
                    }
                },
        )
    }
}

@Composable
private fun Header(state: PrivateUsdProgressState) {
    val c = ZappTheme.colors
    val result = state.result
    Column(verticalArrangement = Arrangement.spacedBy(ZappTheme.spacing.sm)) {
        when {
            result?.isSuccess == true -> {
                ZappSuccessHeader(title = result.title, subtitle = result.body)
            }

            result != null -> {
                BasicText(text = result.title.getValue(), style = ZappTheme.typography.display.copy(color = c.text))
                BasicText(text = result.body.getValue(), style = ZappTheme.typography.body.copy(color = c.textMuted))
            }

            else -> {
                BasicText(
                    text = stringResource(R.string.convert_progress_title),
                    style = ZappTheme.typography.display.copy(color = c.text),
                )
            }
        }
        if (result?.isSuccess != true) {
            state.amounts?.let {
                BasicText(text = it.getValue(), style = ZappTheme.typography.caption.copy(color = c.textMuted))
            }
        }
    }
}

@Composable
private fun Problem(
    message: String,
    onRetry: () -> Unit,
) {
    val c = ZappTheme.colors
    ZappBorderedCard(borderColor = c.danger, verticalArrangement = Arrangement.spacedBy(ZappTheme.spacing.md)) {
        ZappSectionLabel(text = stringResource(R.string.convert_problem_title), color = c.danger)
        BasicText(text = message, style = ZappTheme.typography.body.copy(color = c.text))
        ZappButton(
            text = stringResource(R.string.convert_problem_retry),
            variant = ZappButtonVariant.Secondary,
            modifier = Modifier.fillMaxWidth(),
            onClick = onRetry,
        )
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
                    problem = null,
                    onRetry = {},
                    callOff = null,
                    showsBackgroundNote = true,
                    primaryButton = null,
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
                    problem = null,
                    onRetry = {},
                    callOff = null,
                    showsBackgroundNote = false,
                    primaryButton = ButtonState(stringRes("Try again")),
                    onBack = {},
                ),
        )
    }
