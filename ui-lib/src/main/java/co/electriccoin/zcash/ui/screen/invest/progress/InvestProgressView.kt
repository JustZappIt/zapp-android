package co.electriccoin.zcash.ui.screen.invest.progress

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.zapp.ZappBottomActionBar
import co.electriccoin.zcash.ui.design.component.zapp.ZappButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappButtonVariant
import co.electriccoin.zcash.ui.design.component.zapp.ZappDoneButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappStep
import co.electriccoin.zcash.ui.design.component.zapp.ZappStepList
import co.electriccoin.zcash.ui.design.component.zapp.ZappStepStatus
import co.electriccoin.zcash.ui.design.component.zapp.ZappSuccessHeader
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.invest.common.INVEST_GAP_LG
import co.electriccoin.zcash.ui.screen.invest.common.INVEST_GAP_SM
import co.electriccoin.zcash.ui.screen.invest.common.INVEST_HORIZONTAL_PADDING
import co.electriccoin.zcash.ui.screen.invest.common.InvestNotice

/** After UpiOfframpProgressView: header (the success header once held), the step list, then Back to Pay. */
@Composable
internal fun InvestProgressView(state: InvestProgressState) {
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
                    .padding(horizontal = INVEST_HORIZONTAL_PADDING.dp, vertical = VERTICAL_PADDING.dp),
        ) {
            if (state.isSuccess) {
                ZappSuccessHeader(title = state.title, subtitle = state.subtitle)
            } else {
                BasicText(text = state.title.getValue(), style = ZappTheme.typography.display.copy(color = c.text))
                state.subtitle?.let {
                    Spacer(Modifier.height(INVEST_GAP_SM.dp))
                    BasicText(text = it.getValue(), style = ZappTheme.typography.body.copy(color = c.textMuted))
                }
            }
            Spacer(Modifier.height(INVEST_GAP_LG.dp))
            ZappStepList(state.steps)
            state.attention?.let {
                Spacer(Modifier.height(INVEST_GAP_LG.dp))
                InvestNotice(body = it.getValue(), isDanger = true) {
                    state.removeButton?.let { remove ->
                        ZappButton(
                            text = remove.text.getValue(),
                            variant = ZappButtonVariant.Ghost,
                            modifier = Modifier.weight(1f),
                            onClick = remove.onClick,
                        )
                    }
                }
            }
            state.checkError?.let {
                Spacer(Modifier.height(INVEST_GAP_LG.dp))
                InvestNotice(body = it.getValue()) {
                    ZappButton(
                        text = stringResource(R.string.invest_progress_check_again),
                        variant = ZappButtonVariant.Ghost,
                        modifier = Modifier.weight(1f),
                        onClick = state.onCheckAgain,
                    )
                }
            }
        }
        ZappBottomActionBar(
            onBack = state.onBack,
            primaryAction = {
                if (state.isSuccess) {
                    ZappDoneButton(
                        text = state.primaryButton.text.getValue(),
                        modifier = Modifier.weight(1f).padding(start = BOTTOM_BAR_GAP.dp),
                        onClick = state.primaryButton.onClick,
                    )
                } else {
                    ZappButton(
                        text = state.primaryButton.text.getValue(),
                        variant = ZappButtonVariant.Secondary,
                        modifier = Modifier.weight(1f).padding(start = BOTTOM_BAR_GAP.dp),
                        onClick = state.primaryButton.onClick,
                    )
                }
            },
        )
    }
}

private const val VERTICAL_PADDING = 16
private const val BOTTOM_BAR_GAP = 12

@PreviewScreens
@Composable
private fun PreviewBuying() {
    ZcashTheme {
        InvestProgressView(
            InvestProgressState(
                title = stringRes("Buying NVIDIA"),
                subtitle = stringRes("$100 · You can leave this screen."),
                isSuccess = false,
                steps =
                    listOf(
                        ZappStep(stringRes("ZEC sent from shielded"), ZappStepStatus.Completed),
                        ZappStep(stringRes("Payment received"), ZappStepStatus.Completed),
                        ZappStep(
                            stringRes("Buying NVIDIA"),
                            ZappStepStatus.InProgress,
                            detailLines = listOf(stringRes("Usually 2–5 minutes.")),
                        ),
                        ZappStep(stringRes("Held privately"), ZappStepStatus.Pending),
                    ),
                attention = null,
                checkError = null,
                onCheckAgain = {},
                primaryButton = ButtonState(stringRes("Back to Pay")),
                removeButton = null,
                onBack = {},
            ),
        )
    }
}

@PreviewScreens
@Composable
private fun PreviewHeld() {
    ZcashTheme {
        InvestProgressView(
            InvestProgressState(
                title = stringRes("Held privately"),
                subtitle = stringRes("0.4410 NVDA is in your private account."),
                isSuccess = true,
                steps =
                    listOf(
                        ZappStep(stringRes("ZEC sent from shielded"), ZappStepStatus.Completed),
                        ZappStep(stringRes("Payment received"), ZappStepStatus.Completed),
                        ZappStep(stringRes("Buying NVIDIA"), ZappStepStatus.Completed),
                        ZappStep(stringRes("Held privately"), ZappStepStatus.Completed),
                    ),
                attention = null,
                checkError = null,
                onCheckAgain = {},
                primaryButton = ButtonState(stringRes("Back to Pay")),
                removeButton = null,
                onBack = {},
            ),
        )
    }
}
