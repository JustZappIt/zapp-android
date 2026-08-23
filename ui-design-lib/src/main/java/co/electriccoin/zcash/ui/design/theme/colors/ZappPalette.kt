package co.electriccoin.zcash.ui.design.theme.colors

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private const val COMPLETION_ARGB = 0xFFF7CD45
private const val LIGHT_COMPLETION_SHADE_ARGB = 0xFFE2A91E
private const val DARK_COMPLETION_SHADE_ARGB = 0xFFDCA31B
private const val ON_COMPLETION_ARGB = 0xFF211A08

@Immutable
data class ZappColors(
    val bg: Color,
    val surface: Color,
    val surfaceAlt: Color,
    val surfaceInput: Color,
    val border: Color,
    val borderStrong: Color,
    val text: Color,
    val textMuted: Color,
    val textSubtle: Color,
    val accent: Color,
    val accentSoft: Color,
    val accentText: Color,
    val completion: Color,
    val completionShade: Color,
    val onCompletion: Color,
    val success: Color,
    val successSoft: Color,
    val danger: Color,
    val dangerSoft: Color,
    val chipBg: Color,
    val overlay: Color,
    val navPill: Color,
    val onAccent: Color,
    val shadow: Color,
)

val LightZappColors =
    ZappColors(
        bg = Color(0xFFFFFFFF),
        surface = Color(0xFFFFFFFF),
        surfaceAlt = Color(0xFFF4F2EE),
        surfaceInput = Color(0xFFF6F4F0),
        border = Color(0xFFEBE7E0),
        borderStrong = Color(0xFFD9D4CA),
        text = Color(0xFF15120D),
        textMuted = Color(0xFF6B645A),
        textSubtle = Color(0xFF9A9288),
        accent = Color(0xFFFF9417),
        accentSoft = Color(0xFFFFE7CC),
        accentText = Color(0xFFA65500),
        completion = Color(COMPLETION_ARGB),
        completionShade = Color(LIGHT_COMPLETION_SHADE_ARGB),
        onCompletion = Color(ON_COMPLETION_ARGB),
        success = Color(0xFF2F9D6A),
        successSoft = Color(0xFFD7F0E3),
        danger = Color(0xFFD94545),
        dangerSoft = Color(0xFFFDE2E0),
        chipBg = Color(0xFFEFECE5),
        overlay = Color(0x73141210),
        navPill = Color(0xFFECEBE5),
        onAccent = Color(0xFFFFFFFF),
        shadow = Color(0x14141210),
    )

val DarkZappColors =
    ZappColors(
        bg = Color(0xFF0F0E0C),
        surface = Color(0xFF171512),
        surfaceAlt = Color(0xFF1B1916),
        surfaceInput = Color(0xFF201D19),
        border = Color(0xFF2A2622),
        borderStrong = Color(0xFF3A342D),
        text = Color(0xFFF6F2EA),
        textMuted = Color(0xFFA59C90),
        textSubtle = Color(0xFF726A60),
        accent = Color(0xFFFF9417),
        accentSoft = Color(0xFF3A2713),
        accentText = Color(0xFFFFB26B),
        completion = Color(COMPLETION_ARGB),
        completionShade = Color(DARK_COMPLETION_SHADE_ARGB),
        onCompletion = Color(ON_COMPLETION_ARGB),
        success = Color(0xFF5FD49C),
        successSoft = Color(0xFF1A2E24),
        danger = Color(0xFFEF6A5F),
        dangerSoft = Color(0xFF2E1A18),
        chipBg = Color(0xFF1F1C18),
        overlay = Color(0x8C000000),
        navPill = Color(0xFF1C1A16),
        onAccent = Color(0xFF1A140B),
        shadow = Color(0x80000000),
    )

internal val LocalZappColors = staticCompositionLocalOf { LightZappColors }

internal val LocalZappDarkMode = compositionLocalOf { false }

object ZappNavBar {
    /**
     * Fixed bottom clearance for scrollable content (LazyColumn / Column).
     * Covers the floating pill nav bar height (pill + padding) above the
     * system navigation bar inset. Add navigationBars inset separately:
     *   val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
     *   PaddingValues(bottom = navBottom + CLEARANCE_DP.dp)
     */
    const val CLEARANCE_DP = 80

    /**
     * Fixed bottom padding for FABs above the floating pill nav bar.
     * Pair with windowInsetsPadding(WindowInsets.navigationBars) so the
     * system nav bar height is handled dynamically:
     *   Modifier.windowInsetsPadding(WindowInsets.navigationBars)
     *            .padding(bottom = FAB_BOTTOM_PADDING_DP.dp)
     */
    const val FAB_BOTTOM_PADDING_DP = 80

    /**
     * Bottom margin for floating buttons (FAB / back) on pushed sub-screens,
     * where the pill nav bar is absent — [FAB_BOTTOM_PADDING_DP] there would
     * strand the buttons 80dp above the thumb zone.
     */
    const val PUSHED_FLOATING_MARGIN_DP = 24
}
