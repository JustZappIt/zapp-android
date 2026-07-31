package co.electriccoin.zcash.ui.screen.home.shieldfunds

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.sdk.extension.typicalFee
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.CheckboxState
import co.electriccoin.zcash.ui.design.component.Spacer
import co.electriccoin.zcash.ui.design.component.ZashiScreenModalBottomSheet
import co.electriccoin.zcash.ui.design.component.rememberScreenModalBottomSheetState
import co.electriccoin.zcash.ui.design.component.zapp.ZappBorderedCard
import co.electriccoin.zcash.ui.design.component.zapp.ZappButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappButtonVariant
import co.electriccoin.zcash.ui.design.component.zapp.ZappModalBottomSheetDragHandle
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.util.TickerLocation.HIDDEN
import co.electriccoin.zcash.ui.design.util.asPrivacySensitive
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.util.CURRENCY_TICKER

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShieldFundsInfoView(
    state: ShieldFundsInfoState?,
    sheetState: SheetState = rememberScreenModalBottomSheetState(),
) {
    ZashiScreenModalBottomSheet(
        state = state,
        sheetState = sheetState,
        containerColor = ZappTheme.colors.surface,
        dragHandle = { ZappModalBottomSheetDragHandle() },
        content = { state, contentPadding ->
            Content(
                modifier = Modifier.weight(1f, false),
                state = state,
                contentPadding = contentPadding
            )
        }
    )
}

@Composable
private fun Content(
    state: ShieldFundsInfoState,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val c = ZappTheme.colors
    Column(
        modifier =
            modifier
                .background(c.surface)
                .verticalScroll(rememberScrollState())
                .padding(
                    start = 24.dp,
                    end = 24.dp,
                    bottom = contentPadding.calculateBottomPadding()
                )
    ) {
        Image(
            painter = painterResource(R.drawable.ic_info_shield),
            contentDescription = null
        )
        Spacer(12.dp)
        BasicText(
            text = stringRes(R.string.home_info_transparent_title).getValue(),
            style = ZappTheme.typography.sectionTitle.copy(color = c.text, fontWeight = FontWeight.SemiBold)
        )
        Spacer(8.dp)
        BasicText(
            text = stringRes(R.string.home_info_transparent_subtitle, CURRENCY_TICKER).getValue(),
            style = ZappTheme.typography.body.copy(color = c.textMuted)
        )
        Spacer(12.dp)
        BasicText(
            text =
                stringRes(
                    R.string.home_info_transparent_message,
                    stringRes(Zatoshi.typicalFee, HIDDEN),
                    CURRENCY_TICKER
                ).getValue(),
            style = ZappTheme.typography.body.copy(color = c.textMuted)
        )
        Spacer(16.dp)
        ZappBorderedCard(borderColor = c.border) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(
                    text = stringRes(R.string.home_info_transparent_subheader).getValue(),
                    style = ZappTheme.typography.rowTitle.copy(color = c.text, fontWeight = FontWeight.Medium)
                )
                Spacer(4.dp)
                Image(
                    painter = painterResource(R.drawable.ic_transparent_small),
                    contentDescription = null
                )
            }
            Spacer(4.dp)
            BasicText(
                text = state.subtitle.getValue(),
                style = ZappTheme.typography.sectionTitle.copy(color = c.text, fontWeight = FontWeight.SemiBold)
            )
        }
        Spacer(16.dp)
        ShieldFundsCheckbox(state = state.checkbox)
        Spacer(20.dp)
        ShieldFundsButton(
            state = state.primaryButton,
            variant = ZappButtonVariant.Primary,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(8.dp)
        ShieldFundsButton(
            state = state.secondaryButton,
            variant = ZappButtonVariant.Ghost,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ShieldFundsButton(
    state: ButtonState,
    variant: ZappButtonVariant,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    ZappButton(
        text = state.text.getValue(),
        variant = variant,
        enabled = state.isEnabled && !state.isLoading,
        modifier = modifier,
        onClick = {
            state.hapticFeedbackType?.let { runCatching { haptic.performHapticFeedback(it) } }
            state.onClick()
        }
    )
}

@Composable
private fun ShieldFundsCheckbox(state: CheckboxState) {
    val c = ZappTheme.colors
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { state.onClick() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(20.dp)
                    .background(if (state.isChecked) c.accent else c.surface, RectangleShape)
                    .border(1.dp, if (state.isChecked) c.accent else c.borderStrong, RectangleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (state.isChecked) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = c.onAccent,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        Spacer(10.dp)
        BasicText(
            text = state.title.getValue(),
            style = ZappTheme.typography.body.copy(color = c.textMuted)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@PreviewScreens
@Composable
private fun Preview() =
    ZcashTheme {
        ShieldFundsInfoView(
            state =
                ShieldFundsInfoState(
                    onBack = {},
                    primaryButton =
                        ButtonState(
                            text = stringRes("Shield"),
                            onClick = {}
                        ),
                    secondaryButton =
                        ButtonState(
                            text = stringRes("Not now"),
                            onClick = {}
                        ),
                    subtitle =
                        stringRes(
                            R.string.home_message_transparent_balance_subtitle,
                            stringRes("0.00").asPrivacySensitive(),
                            CURRENCY_TICKER
                        ),
                    checkbox =
                        CheckboxState(
                            title = stringRes(R.string.home_info_transparent_checkbox),
                            onClick = {},
                            isChecked = false
                        )
                )
        )
    }
