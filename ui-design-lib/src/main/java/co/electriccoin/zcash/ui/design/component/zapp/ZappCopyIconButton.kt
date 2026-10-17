package co.electriccoin.zcash.ui.design.component.zapp

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
    ZappIconButton(
        icon = if (isCopied) Icons.Default.Check else Icons.Default.ContentCopy,
        contentDescription = contentDescription,
        onClick = onClick,
        modifier = modifier,
        tint = if (isCopied) c.success else c.textMuted,
    )
}
