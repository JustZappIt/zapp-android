package co.electriccoin.zcash.ui.design.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import co.electriccoin.zcash.ui.design.theme.colors.DarkZappColors
import co.electriccoin.zcash.ui.design.theme.colors.DefaultZappTextStyles
import co.electriccoin.zcash.ui.design.theme.colors.LightZappColors
import co.electriccoin.zcash.ui.design.theme.colors.LocalZappColors
import co.electriccoin.zcash.ui.design.theme.colors.LocalZappDarkMode
import co.electriccoin.zcash.ui.design.theme.colors.LocalZappTypography
import co.electriccoin.zcash.ui.design.theme.colors.ZappColors
import co.electriccoin.zcash.ui.design.theme.colors.ZappTextStyles

/**
 * Provides Zapp design tokens to the composition. Layers on top of
 * [ZcashTheme] without overriding Material3 so Zashi-inherited screens
 * keep their existing look.
 */
@Composable
fun ProvideZappTheme(
    darkOverride: Boolean? = null,
    content: @Composable () -> Unit,
) {
    val isDark = darkOverride ?: isSystemInDarkTheme()
    val colors = if (isDark) DarkZappColors else LightZappColors
    CompositionLocalProvider(
        LocalZappColors provides colors,
        LocalZappDarkMode provides isDark,
        LocalZappTypography provides DefaultZappTextStyles,
        content = content,
    )
}

object ZappTheme {
    val colors: ZappColors
        @Composable
        @ReadOnlyComposable
        get() = LocalZappColors.current

    val typography: ZappTextStyles
        @Composable
        @ReadOnlyComposable
        get() = LocalZappTypography.current

    val spacing: ZappSpacing get() = ZappSpacing

    val isDark: Boolean
        @Composable
        @ReadOnlyComposable
        get() = LocalZappDarkMode.current
}
