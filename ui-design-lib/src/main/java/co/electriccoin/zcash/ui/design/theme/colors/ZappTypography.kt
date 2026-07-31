package co.electriccoin.zcash.ui.design.theme.colors

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

@Immutable
data class ZappTextStyles(
    val screenTitle: TextStyle,
    val sectionTitle: TextStyle,
    val display: TextStyle,
    val displaySecondary: TextStyle,
    val balanceDisplay: TextStyle,
    val balanceFraction: TextStyle,
    val eyebrow: TextStyle,
    val groupLabel: TextStyle,
    val rowTitle: TextStyle,
    val rowSubtitle: TextStyle,
    val body: TextStyle,
    val caption: TextStyle,
    val chip: TextStyle,
    val button: TextStyle,
    val buttonSmall: TextStyle,
    val mono: TextStyle,
)

private val sansFamily = FontFamily.Default
private val monoFamily = FontFamily.Monospace

val DefaultZappTextStyles =
    ZappTextStyles(
        screenTitle =
            TextStyle(
                fontFamily = sansFamily,
                fontSize = 22.sp,
                lineHeight = 28.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp,
            ),
        sectionTitle =
            TextStyle(
                fontFamily = sansFamily,
                fontSize = 18.sp,
                lineHeight = 24.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.3).sp,
            ),
        display =
            TextStyle(
                fontFamily = sansFamily,
                fontSize = 32.sp,
                lineHeight = 36.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-1.0).sp,
            ),
        displaySecondary =
            TextStyle(
                fontFamily = sansFamily,
                fontSize = 24.sp,
                lineHeight = 28.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp,
            ),
        balanceDisplay =
            TextStyle(
                fontFamily = sansFamily,
                fontSize = 35.2.sp,
                lineHeight = 39.6.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = (-1.0).sp,
            ),
        balanceFraction =
            TextStyle(
                fontFamily = sansFamily,
                fontSize = 19.8.sp,
                lineHeight = 26.4.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.3).sp,
            ),
        eyebrow =
            TextStyle(
                fontFamily = sansFamily,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.0.sp,
            ),
        groupLabel =
            TextStyle(
                fontFamily = sansFamily,
                fontSize = 10.sp,
                lineHeight = 14.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.0.sp,
            ),
        rowTitle =
            TextStyle(
                fontFamily = sansFamily,
                fontSize = 15.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.SemiBold,
            ),
        rowSubtitle =
            TextStyle(
                fontFamily = sansFamily,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.Normal,
            ),
        body =
            TextStyle(
                fontFamily = sansFamily,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Normal,
            ),
        caption =
            TextStyle(
                fontFamily = sansFamily,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.Medium,
            ),
        chip =
            TextStyle(
                fontFamily = sansFamily,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.4.sp,
            ),
        button =
            TextStyle(
                fontFamily = sansFamily,
                fontSize = 15.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.SemiBold,
            ),
        buttonSmall =
            TextStyle(
                fontFamily = sansFamily,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.SemiBold,
            ),
        mono =
            TextStyle(
                fontFamily = monoFamily,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.Medium,
            ),
    )

internal val LocalZappTypography = staticCompositionLocalOf { DefaultZappTextStyles }
