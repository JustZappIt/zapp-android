package co.electriccoin.zcash.ui.screen.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.Spacer
import co.electriccoin.zcash.ui.design.component.ZashiButton
import co.electriccoin.zcash.ui.design.component.ZashiButtonDefaults
import co.electriccoin.zcash.ui.design.component.ZashiScreenModalBottomSheet
import co.electriccoin.zcash.ui.design.component.rememberScreenModalBottomSheetState

/**
 * Shared chrome for info/help bottom-sheet dialogs.
 *
 * Handles [ZashiScreenModalBottomSheet], scrollable [Column], and padding.
 * When [primaryButton] is non-null the shell renders it (and optionally [secondaryButton])
 * below a 32 dp spacer. Pass null to manage buttons yourself inside [content].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoBottomSheetView(
    onBack: () -> Unit,
    primaryButton: ButtonState? = null,
    secondaryButton: ButtonState? = null,
    sheetState: SheetState = rememberScreenModalBottomSheetState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    ZashiScreenModalBottomSheet(
        onDismissRequest = onBack,
        sheetState = sheetState,
    ) { contentPadding ->
        Column(
            modifier =
                Modifier
                    .weight(1f, false)
                    .verticalScroll(rememberScrollState())
                    .padding(
                        start = 24.dp,
                        end = 24.dp,
                        bottom = contentPadding.calculateBottomPadding(),
                    ),
        ) {
            content()
            if (primaryButton != null) {
                Spacer(32.dp)
                secondaryButton?.let {
                    ZashiButton(
                        state = it,
                        modifier = Modifier.fillMaxWidth(),
                        defaultPrimaryColors = ZashiButtonDefaults.secondaryColors(),
                    )
                    Spacer(12.dp)
                }
                ZashiButton(
                    state = primaryButton,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
