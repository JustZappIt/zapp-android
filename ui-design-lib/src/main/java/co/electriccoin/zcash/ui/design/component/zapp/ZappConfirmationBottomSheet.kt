package co.electriccoin.zcash.ui.design.component.zapp

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.ModalBottomSheetState
import co.electriccoin.zcash.ui.design.component.rememberInScreenModalBottomSheetState
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.getValue

/**
 * Black/amber Zapp-styled confirmation bottom sheet — the Zapp counterpart to
 * [co.electriccoin.zcash.ui.design.component.ZashiConfirmationBottomSheet], which renders the upstream
 * Zashi palette. A null [state] keeps it hidden; a non-null one animates it up.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZappConfirmationBottomSheet(state: ZappConfirmationState?) {
    val sheetState = rememberInScreenModalBottomSheetState()
    var current by remember { mutableStateOf(state) }

    current?.let { active ->
        ModalBottomSheet(
            onDismissRequest = active.onBack,
            modifier = Modifier.statusBarsPadding(),
            sheetState = sheetState,
            containerColor = ZappTheme.colors.surface,
            scrimColor = ZappTheme.colors.overlay,
            shape = RoundedCornerShape(topStart = SHEET_CORNER.dp, topEnd = SHEET_CORNER.dp),
            dragHandle = { ZappModalBottomSheetDragHandle() },
            properties = ModalBottomSheetProperties(shouldDismissOnBackPress = false),
            contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        ) {
            BackHandler { active.onBack() }
            // weight(1f, false): the scroll column yields space to the bottom-inset Spacer below so tall
            // content scrolls internally instead of running under the navigation bar (upstream idiom).
            ZappConfirmationContent(active, Modifier.weight(1f, false))
            Spacer(modifier = Modifier.windowInsetsBottomHeight(WindowInsets.systemBars))
            LaunchedEffect(Unit) { sheetState.show() }
        }
    }

    LaunchedEffect(state) {
        if (state == null) sheetState.hide()
        current = state
    }
}

data class ZappConfirmationState(
    val title: StringResource,
    val message: StringResource,
    val primaryButton: ButtonState,
    val secondaryButton: ButtonState? = null,
    val isDestructive: Boolean = false,
    override val onBack: () -> Unit,
) : ModalBottomSheetState

@Composable
private fun ZappConfirmationContent(state: ZappConfirmationState, modifier: Modifier = Modifier) {
    val c = ZappTheme.colors
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = HORIZONTAL_PADDING.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(GAP_LG.dp))
        BasicText(
            text = state.title.getValue(),
            style = ZappTheme.typography.sectionTitle.copy(color = c.text, fontWeight = FontWeight.SemiBold),
        )
        Spacer(modifier = Modifier.height(GAP_SM.dp))
        BasicText(
            text = state.message.getValue(),
            style = ZappTheme.typography.body.copy(color = c.textMuted, textAlign = TextAlign.Center),
        )
        Spacer(modifier = Modifier.height(GAP_LG.dp))
        ZappButton(
            text = state.primaryButton.text.getValue(),
            enabled = state.primaryButton.isEnabled,
            variant = if (state.isDestructive) ZappButtonVariant.Danger else ZappButtonVariant.Primary,
            modifier = Modifier.fillMaxWidth(),
            onClick = state.primaryButton.onClick,
        )
        state.secondaryButton?.let { secondary ->
            Spacer(modifier = Modifier.height(GAP_SM.dp))
            ZappButton(
                text = secondary.text.getValue(),
                enabled = secondary.isEnabled,
                variant = ZappButtonVariant.Ghost,
                modifier = Modifier.fillMaxWidth(),
                onClick = secondary.onClick,
            )
        }
        Spacer(modifier = Modifier.height(GAP_LG.dp))
    }
}

private const val SHEET_CORNER = 20
private const val HORIZONTAL_PADDING = 24
private const val GAP_SM = 8
private const val GAP_LG = 20
