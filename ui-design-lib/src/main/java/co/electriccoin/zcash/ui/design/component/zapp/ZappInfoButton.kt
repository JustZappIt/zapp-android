package co.electriccoin.zcash.ui.design.component.zapp

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** The "i" at the right of a screen header, opening what the screen does. */
@Composable
fun ZappInfoButton(
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ZappIconButton(
        icon = Icons.Default.Info,
        contentDescription = contentDescription,
        onClick = onClick,
        modifier = modifier,
        iconSize = ICON_SIZE,
    )
}

private val ICON_SIZE = 24.dp
