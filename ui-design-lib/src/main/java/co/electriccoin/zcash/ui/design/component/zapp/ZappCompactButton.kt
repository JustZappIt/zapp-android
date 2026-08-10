package co.electriccoin.zcash.ui.design.component.zapp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.design.theme.ZappTheme

/** Compact accent action for dense balance and summary rows. */
@Composable
fun ZappCompactButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = ZappTheme.colors
    Box(
        modifier =
            modifier
                .defaultMinSize(minHeight = MIN_TOUCH_TARGET.dp)
                .clickable(enabled = enabled, onClick = onClick)
                .semantics(mergeDescendants = true) {
                    contentDescription = text
                    role = Role.Button
                    if (!enabled) disabled()
                },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .background(if (enabled) colors.accent else colors.surfaceAlt, RectangleShape)
                    .padding(horizontal = HORIZONTAL_PADDING.dp, vertical = VERTICAL_PADDING.dp),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text = text,
                style =
                    ZappTheme.typography.buttonSmall.copy(
                        color = if (enabled) colors.onAccent else colors.textSubtle,
                    ),
            )
        }
    }
}

private const val HORIZONTAL_PADDING = 12
private const val VERTICAL_PADDING = 6
private const val MIN_TOUCH_TARGET = 48
