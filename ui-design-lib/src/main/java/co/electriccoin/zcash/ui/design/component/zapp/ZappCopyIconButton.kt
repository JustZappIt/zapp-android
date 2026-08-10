package co.electriccoin.zcash.ui.design.component.zapp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.design.theme.ZappTheme

/** Copy affordance for key/address cards; flips to a green check while [isCopied] holds. */
@Composable
fun ZappCopyIconButton(
    isCopied: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = ZappTheme.colors
    Box(
        modifier =
            modifier
                .size(TOUCH_TARGET)
                .clickable(onClick = onClick)
                .semantics {
                    this.contentDescription = contentDescription
                    this.role = Role.Button
                },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (isCopied) Icons.Default.Check else Icons.Default.ContentCopy,
            contentDescription = null,
            tint = if (isCopied) c.success else c.textMuted,
            modifier = Modifier.size(ICON_SIZE),
        )
    }
}

private val TOUCH_TARGET = 48.dp
private val ICON_SIZE = 20.dp
