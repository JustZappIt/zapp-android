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

/** Muted colors for a secondary action sharing a screen with a [zappAccentButtonColors] primary. */
@Composable
fun zappSecondaryButtonColors(): ZashiButtonColors {
    val c = ZappTheme.colors
    return ZashiButtonColors(
        containerColor = c.surfaceAlt,
        contentColor = c.text,
        borderColor = Color.Unspecified,
        disabledContainerColor = c.surfaceAlt,
        disabledContentColor = c.textSubtle,
        disabledBorderColor = Color.Unspecified,
    )
}

/**
 * Tinted colors for a secondary action that still belongs to the accent story. `accentText` is only
 * legible on `accentSoft`, never on full `accent`, so the two tokens always travel together.
 */
@Composable
fun zappAccentSoftButtonColors(): ZashiButtonColors {
    val c = ZappTheme.colors
    return ZashiButtonColors(
        containerColor = c.accentSoft,
        contentColor = c.accentText,
        borderColor = Color.Unspecified,
        disabledContainerColor = c.surfaceAlt,
        disabledContentColor = c.textSubtle,
        disabledBorderColor = Color.Unspecified,
    )
}

/** Danger colors for an action the user should hesitate over, rendered with `ZashiButton`. */
@Composable
fun zappDangerButtonColors(): ZashiButtonColors {
    val c = ZappTheme.colors
    return ZashiButtonColors(
        containerColor = c.dangerSoft,
        contentColor = c.danger,
        borderColor = Color.Unspecified,
        disabledContainerColor = c.surfaceAlt,
        disabledContentColor = c.textSubtle,
        disabledBorderColor = Color.Unspecified,
    )
}
