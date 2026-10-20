package co.electriccoin.zcash.ui.design.component.zapp

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.design.theme.ZappTheme

/** A square checkbox and its sentence; the whole row toggles it. */
@Composable
fun ZappCheckboxRow(
    text: String,
    isChecked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = ZappTheme.colors
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .toggleable(value = isChecked, role = Role.Checkbox, onValueChange = { onClick() })
                .padding(vertical = ZappTheme.spacing.md),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(ZappTheme.spacing.lg),
    ) {
        Box(
            modifier =
                Modifier
                    .size(BOX_SIZE)
                    .background(if (isChecked) c.accent else Color.Transparent, RectangleShape)
                    .border(BorderStroke(1.dp, if (isChecked) c.accent else c.borderStrong), RectangleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (isChecked) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = c.onAccent,
                    modifier = Modifier.size(CHECK_SIZE),
                )
            }
        }
        BasicText(text = text, style = ZappTheme.typography.body.copy(color = c.text), modifier = Modifier.weight(1f))
    }
}

private val BOX_SIZE = 20.dp
private val CHECK_SIZE = 14.dp
