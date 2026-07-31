package co.electriccoin.zcash.ui.design.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import co.electriccoin.zcash.ui.design.theme.typography.InterFontFamily
import co.electriccoin.zcash.ui.design.theme.typography.RobotoMonoFontFamily

enum class StyledStringFont {
    INTER,
    ROBOTO_MONO
}

@Composable
fun StyledStringFont.getFontFamily(): FontFamily =
    when (this) {
        StyledStringFont.INTER -> InterFontFamily
        StyledStringFont.ROBOTO_MONO -> RobotoMonoFontFamily
    }
