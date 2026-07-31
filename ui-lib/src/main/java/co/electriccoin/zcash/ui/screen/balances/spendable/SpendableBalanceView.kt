package co.electriccoin.zcash.ui.screen.balances.spendable

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.LottieProgress
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
import co.electriccoin.zcash.ui.design.util.ImageResource
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.imageRes
import co.electriccoin.zcash.ui.design.util.loadingImageRes
import co.electriccoin.zcash.ui.design.util.orHiddenString
import co.electriccoin.zcash.ui.design.util.stringRes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpendableBalanceView(
    state: SpendableBalanceState?,
    sheetState: SheetState = rememberScreenModalBottomSheetState(),
) {
    ZashiScreenModalBottomSheet(
        state = state,
        sheetState = sheetState,
        containerColor = ZappTheme.colors.surface,
        dragHandle = { ZappModalBottomSheetDragHandle() },
        content = { state, contentPadding ->
            BottomSheetContent(state, contentPadding, modifier = Modifier.weight(1f, false))
        },
    )
}

@Composable
internal fun BottomSheetContent(
    state: SpendableBalanceState,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier
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
        BasicText(
            modifier = Modifier.fillMaxWidth(),
            text = state.title.getValue(),
            style =
                ZappTheme.typography.sectionTitle.copy(
                    color = c.text,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
        )
        Spacer(12.dp)
        BasicText(
            text = state.message.getValue(),
            style = ZappTheme.typography.body.copy(color = c.textMuted)
        )
        Spacer(32.dp)
        state.rows.forEachIndexed { index, rowState ->
            if (index != 0) {
                Spacer(12.dp)
            }
            BalanceActionRow(rowState)
        }
        state.shieldButton?.let {
            Spacer(32.dp)
            BalanceShieldButton(it)
        }
        Spacer(32.dp)
        PositiveButton(
            state = state.positive,
            variant = if (state.shieldButton != null) ZappButtonVariant.Ghost else ZappButtonVariant.Primary,
        )
    }
}

@Composable
private fun BalanceActionRow(state: SpendableBalanceRowState) {
    val c = ZappTheme.colors
    val isLoading = state.icon is ImageResource.Loading
    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicText(
            text = state.title.getValue(),
            style = ZappTheme.typography.body.copy(color = c.textMuted)
        )
        Spacer(1f)
        when (state.icon) {
            is ImageResource.ByDrawable -> {
                Image(
                    modifier = Modifier.size(20.dp),
                    painter = painterResource(state.icon.resource),
                    contentDescription = null
                )
            }

            ImageResource.Loading -> {
                LottieProgress(modifier = Modifier.size(20.dp))
            }

            is ImageResource.DisplayString -> {
                // do nothing
            }
        }
        Spacer(8.dp)
        SelectionContainer {
            BasicText(
                text =
                    state.value orHiddenString
                        stringRes(co.electriccoin.zcash.ui.design.R.string.hide_balance_placeholder),
                style =
                    ZappTheme.typography.body.copy(
                        color = if (isLoading) c.textMuted else c.text,
                        fontWeight = FontWeight.Medium
                    )
            )
        }
    }
}

@Composable
private fun BalanceShieldButton(state: SpendableBalanceShieldButtonState) {
    val c = ZappTheme.colors
    val haptic = LocalHapticFeedback.current
    ZappBorderedCard(borderColor = c.border) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                modifier = Modifier.weight(1f),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BasicText(
                        text = stringResource(R.string.balance_action_shield_button_header),
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
                    text =
                        stringRes(state.amount)
                            orHiddenString
                            stringRes(co.electriccoin.zcash.ui.design.R.string.hide_balance_placeholder),
                    style = ZappTheme.typography.sectionTitle.copy(color = c.text, fontWeight = FontWeight.SemiBold)
                )
            }
            Spacer(8.dp)
            ZappButton(
                text = stringResource(R.string.balance_action_shield),
                variant = ZappButtonVariant.Primary,
                onClick = {
                    runCatching { haptic.performHapticFeedback(HapticFeedbackType.Confirm) }
                    state.onShieldClick()
                }
            )
        }
    }
}

@Composable
private fun PositiveButton(
    state: ButtonState,
    variant: ZappButtonVariant,
) {
    val haptic = LocalHapticFeedback.current
    ZappButton(
        text = state.text.getValue(),
        variant = variant,
        enabled = state.isEnabled && !state.isLoading,
        modifier = Modifier.fillMaxWidth(),
        onClick = {
            state.hapticFeedbackType?.let { runCatching { haptic.performHapticFeedback(it) } }
            state.onClick()
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@PreviewScreens
@Composable
private fun Preview() =
    ZcashTheme {
        SpendableBalanceView(
            state =
                SpendableBalanceState(
                    title = stringRes("Spendable balance"),
                    message = stringRes("Subtitle"),
                    positive =
                        ButtonState(
                            text = stringRes("Dismiss")
                        ),
                    onBack = {},
                    rows =
                        listOf(
                            SpendableBalanceRowState(
                                title = stringRes("Pending"),
                                icon = loadingImageRes(),
                                value = stringRes("Value")
                            ),
                            SpendableBalanceRowState(
                                title = stringRes("Shielded"),
                                icon = imageRes(R.drawable.ic_balance_shield),
                                value = stringRes("Value")
                            )
                        ),
                    shieldButton =
                        SpendableBalanceShieldButtonState(
                            amount = Zatoshi(10000),
                            onShieldClick = {}
                        )
                )
        )
    }
