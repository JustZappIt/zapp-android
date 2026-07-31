package co.electriccoin.zcash.ui.design.component.zapp

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import co.electriccoin.zcash.ui.design.component.ZashiButtonColors
import co.electriccoin.zcash.ui.design.theme.ZappTheme

/** Swiss accent (yellow) colors for a primary call-to-action rendered with `ZashiButton`. */
@Composable
fun zappAccentButtonColors(): ZashiButtonColors {
    val c = ZappTheme.colors
    return ZashiButtonColors(
        containerColor = c.accent,
        contentColor = c.onAccent,
        borderColor = Color.Unspecified,
        disabledContainerColor = c.accentSoft,
        disabledContentColor = c.textMuted,
        disabledBorderColor = Color.Unspecified,
    )
}
