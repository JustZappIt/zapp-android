package co.electriccoin.zcash.ui.screen.swap.slippage

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.Spacer
import co.electriccoin.zcash.ui.design.component.ZashiCard
import co.electriccoin.zcash.ui.design.component.ZashiScreenModalBottomSheet
import co.electriccoin.zcash.ui.design.component.zapp.ZappBackButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappButton
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.theme.colors.ZashiColors
import co.electriccoin.zcash.ui.design.theme.typography.ZashiTypography
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.styledStringResource
import co.electriccoin.zcash.ui.screen.swap.slippage.SwapSlippageInfoState.Mode.HIGH
import co.electriccoin.zcash.ui.screen.swap.slippage.SwapSlippageInfoState.Mode.LOW
import co.electriccoin.zcash.ui.screen.swap.slippage.SwapSlippageInfoState.Mode.MEDIUM

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwapSlippageView(state: SwapSlippageState?) {
    ZashiScreenModalBottomSheet(
        state = state,
        dragHandle = null,
        content = { innerState, _ ->
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
            ) {
                Spacer(24.dp)
                Text(
                    text = stringResource(R.string.swap_slippage_title),
                    style = ZashiTypography.header6,
                    fontWeight = FontWeight.SemiBold,
                    color = ZashiColors.Text.textPrimary
                )
                Spacer(8.dp)
                Text(
                    text = stringResource(R.string.swap_slippage_subtitle),
                    style = ZashiTypography.textSm,
                    color = ZashiColors.Text.textTertiary
                )
                Spacer(24.dp)
                SlippagePicker(state = innerState.picker)
                if (innerState.info != null) {
                    Spacer(20.dp)
                    SlippageInfoCard(innerState.info)
                }
                if (innerState.warning != null) {
                    Spacer(20.dp)
                    ZashiCard(
                        modifier = Modifier.fillMaxWidth(),
                        colors =
                            CardDefaults.cardColors(
                                containerColor = ZashiColors.Utility.WarningYellow.utilityOrange100
                            ),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)
                    ) {
                        Text(
                            text = innerState.warning.getValue(),
                            style = ZashiTypography.textXs,
                            color = ZashiColors.Utility.WarningYellow.utilityOrange800
                        )
                    }
                }
                Spacer(8.dp)
            }
            val c = ZappTheme.colors
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(c.surface)
                        .border(BorderStroke(1.dp, c.border), RectangleShape)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                ZappBackButton(onClick = innerState.onBack)
                ZappButton(
                    modifier = Modifier.weight(1f).padding(start = 12.dp),
                    text = innerState.primary.text.getValue(),
                    enabled = innerState.primary.isEnabled,
                    onClick = innerState.primary.onClick,
                )
            }
        },
    )
}

@Composable
private fun SlippageInfoCard(state: SwapSlippageInfoState) {
    val containerColor by animateColorAsState(state.mode.containerColor)
    val titleColor by animateColorAsState(state.mode.titleColor)

    ZashiCard(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = containerColor
            ),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Text(
            text = state.title.getValue() + state.additional?.let { " " + it.getValue() }.orEmpty(),
            style = ZashiTypography.textSm,
            fontWeight = FontWeight.SemiBold,
            color = titleColor
        )
    }
}

private val SwapSlippageInfoState.Mode.containerColor: Color
    @Composable get() =
        when (this) {
            LOW -> ZashiColors.Utility.Gray.utilityGray50
            MEDIUM -> ZashiColors.Utility.WarningYellow.utilityOrange50
            HIGH -> ZashiColors.Utility.ErrorRed.utilityError50
        }

private val SwapSlippageInfoState.Mode.titleColor: Color
    @Composable get() =
        when (this) {
            LOW -> ZashiColors.Utility.Gray.utilityGray600
            MEDIUM -> ZashiColors.Utility.WarningYellow.utilityOrange600
            HIGH -> ZashiColors.Utility.ErrorRed.utilityError600
        }

@PreviewScreens
@Composable
private fun Preview() =
    ZcashTheme {
        SwapSlippageView(
            state =
                SwapSlippageState(
                    onBack = {},
                    picker = SlippagePickerState { },
                    info =
                        SwapSlippageInfoState(
                            title = stringRes("Title"),
                            additional = stringRes("Title"),
                            mode = SwapSlippageInfoState.Mode.HIGH,
                        ),
                    warning =
                        styledStringResource(
                            R.string.swap_slippage_low_warning,
                            null as androidx.compose.ui.text.font.FontWeight?,
                            "Warning",
                            "under 2%",
                            "2% or higher"
                        ),
                    primary =
                        ButtonState(
                            text = stringRes("Confirm")
                        ),
                )
        )
    }
