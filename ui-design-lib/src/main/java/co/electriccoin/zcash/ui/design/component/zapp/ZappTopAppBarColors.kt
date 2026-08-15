package co.electriccoin.zcash.ui.design.component.zapp

import androidx.compose.runtime.Composable
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.theme.internal.DefaultTopAppBarColors
import co.electriccoin.zcash.ui.design.theme.internal.TopAppBarColors

/**
 * Zapp-palette colors for the structural `ZashiSmallTopAppBar`. The container matches
 * `ZappTheme.colors.bg` so the bar dissolves into a scaffold body drawn on the same token instead
 * of banding a lighter strip across the top of the screen.
 */
@Composable
fun zappTopAppBarColors(): TopAppBarColors {
    val c = ZappTheme.colors
    return DefaultTopAppBarColors(
        containerColor = c.bg,
        navigationColor = c.text,
        titleColor = c.text,
        subTitleColor = c.textMuted,
        actionColor = c.text,
    )
}
