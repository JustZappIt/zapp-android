package co.electriccoin.zcash.ui.design.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.design.theme.colors.ZashiColors
import co.electriccoin.zcash.ui.design.theme.dimensions.ZashiDimensions
import com.valentinilk.shimmer.LocalShimmerTheme
import com.valentinilk.shimmer.ShimmerBounds
import com.valentinilk.shimmer.rememberShimmer

@Composable
fun rememberZashiShimmer() =
    rememberShimmer(
        ShimmerBounds.View,
        LocalShimmerTheme.current.copy(
            animationSpec =
                infiniteRepeatable(
                    animation =
                        tween(
                            durationMillis = 750,
                            easing = LinearEasing,
                            delayMillis = 450,
                        ),
                    repeatMode = RepeatMode.Restart,
                )
        )
    )

@Composable
fun ShimmerCircle(
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    color: Color = ZashiColors.Surfaces.bgSecondary
) {
    Box(
        modifier =
            modifier
                .size(size)
                .background(color, CircleShape)
    )
}

@Composable
fun ShimmerRectangle(
    width: Dp = 40.dp,
    height: Dp = 20.dp,
    color: Color = ZashiColors.Surfaces.bgSecondary,
    shape: Shape = RoundedCornerShape(ZashiDimensions.Radius.radiusSm)
) {
    Box(
        modifier =
            Modifier
                .width(width)
                .height(height)
                .background(color, shape)
    )
}

@Composable
fun ShimmerRectangle(
    modifier: Modifier = Modifier,
    color: Color = ZashiColors.Surfaces.bgSecondary,
    shape: Shape = RoundedCornerShape(ZashiDimensions.Radius.radiusSm)
) {
    Box(
        modifier =
            modifier
                .background(color, shape)
    )
}

@Composable
fun ShimmerableText(
    text: String?,
    shimmerText: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight? = null,
    color: Color = Color.Unspecified,
    maxLines: Int = 1,
    textAlign: TextAlign = TextAlign.Start,
) {
    if (text == null) {
        with(
            measureTextStyle(
                text = shimmerText,
                style = style.copy(fontWeight = fontWeight ?: style.fontWeight),
            )
        ) {
            ShimmerRectangle(
                modifier =
                    Modifier
                        .width(size.widthDp)
                        .height(size.heightDp)
                        .padding(1.dp),
                color = ZashiColors.Surfaces.bgTertiary,
            )
        }
    } else {
        ZashiAutoSizeText(
            modifier = modifier,
            text = text,
            style = style,
            fontWeight = fontWeight,
            color = color,
            maxLines = maxLines,
            textAlign = textAlign
        )
    }
}
