package co.electriccoin.zcash.ui.design.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight

data class StyledStringStyle(
    val color: StringResourceColor? = null,
    val fontWeight: FontWeight? = null,
    val font: StyledStringFont? = null,
)

@Composable
fun StyledStringStyle.toSpanStyle(): SpanStyle =
    SpanStyle(
        color = color?.getColor() ?: Color.Unspecified,
        fontWeight = fontWeight,
        fontFamily = font?.getFontFamily()
    )
