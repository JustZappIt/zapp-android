package co.electriccoin.zcash.ui.design.component.zapp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.design.theme.ZappTheme

/**
 * Centered pill drag handle for Zapp-styled modal bottom sheets. Transparent — it sits on the
 * sheet's own surface container, so pair it with `containerColor = ZappTheme.colors.surface`.
 */
@Composable
fun ZappModalBottomSheetDragHandle(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth().height(DRAG_HANDLE_AREA.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Spacer(
            modifier =
                Modifier
                    .padding(top = DRAG_HANDLE_TOP.dp)
                    .height(DRAG_HANDLE_THICKNESS.dp)
                    .width(DRAG_HANDLE_WIDTH.dp)
                    .background(ZappTheme.colors.borderStrong, CircleShape),
        )
    }
}

private const val DRAG_HANDLE_AREA = 40
private const val DRAG_HANDLE_TOP = 8
private const val DRAG_HANDLE_THICKNESS = 5
private const val DRAG_HANDLE_WIDTH = 42
