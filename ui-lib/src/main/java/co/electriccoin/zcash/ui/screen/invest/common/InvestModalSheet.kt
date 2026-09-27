package co.electriccoin.zcash.ui.screen.invest.common

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.design.component.rememberInScreenModalBottomSheetState
import co.electriccoin.zcash.ui.design.component.zapp.ZappModalBottomSheetDragHandle
import co.electriccoin.zcash.ui.design.theme.ZappTheme

/**
 * The review sheets' frame, from ZappConfirmationBottomSheet: the same surface, handle and back handling, with the
 * content the caller draws. A null [state] keeps it hidden, and the last state stays drawn while it slides away.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T : Any> InvestModalSheet(
    state: T?,
    onDismiss: (T) -> Unit,
    content: @Composable ColumnScope.(T) -> Unit,
) {
    val sheetState = rememberInScreenModalBottomSheetState()
    var current by remember { mutableStateOf(state) }

    current?.let { active ->
        ModalBottomSheet(
            onDismissRequest = { onDismiss(active) },
            modifier = Modifier.statusBarsPadding(),
            sheetState = sheetState,
            containerColor = ZappTheme.colors.surface,
            scrimColor = ZappTheme.colors.overlay,
            shape = RoundedCornerShape(topStart = SHEET_CORNER.dp, topEnd = SHEET_CORNER.dp),
            dragHandle = { ZappModalBottomSheetDragHandle() },
            properties = ModalBottomSheetProperties(shouldDismissOnBackPress = false),
            contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        ) {
            BackHandler { onDismiss(active) }
            content(active)
            Spacer(modifier = Modifier.windowInsetsBottomHeight(WindowInsets.systemBars))
            LaunchedEffect(Unit) { sheetState.show() }
        }
    }

    LaunchedEffect(state) {
        if (state == null) sheetState.hide()
        current = state
    }
}

private const val SHEET_CORNER = 20
