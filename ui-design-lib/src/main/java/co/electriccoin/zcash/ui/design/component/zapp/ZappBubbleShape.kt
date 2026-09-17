package co.electriccoin.zcash.ui.design.component.zapp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * A message bubble: a lightly rounded box with a small tail on the side the message came from,
 * level with the last line. The tail is what marks the sender at a glance, and it only reads as
 * one shape on a softened box — the deliberate exception to the sharp-corner rule.
 */
class ZappBubbleShape(
    private val tail: Tail
) : Shape {
    enum class Tail { LEADING, TRAILING }

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val radius = with(density) { CORNER_RADIUS.toPx() }
        val depth = with(density) { TAIL_DEPTH.toPx() }
        val halfTail = with(density) { TAIL_HEIGHT.toPx() } / 2
        val anchor = with(density) { TAIL_ANCHOR_FROM_BOTTOM.toPx() }
        val tailOnLeft = (tail == Tail.LEADING) == (layoutDirection == LayoutDirection.Ltr)
        val box = Rect(Offset(if (tailOnLeft) depth else 0f, 0f), Size(size.width - depth, size.height))
        val midY = box.bottom - minOf(box.height / 2, anchor)

        val path =
            Path().apply {
                moveTo(box.left + radius, box.top)
                lineTo(box.right - radius, box.top)
                arcTo(Rect(Offset(box.right - radius, box.top + radius), radius), -QUARTER_TURN, QUARTER_TURN, false)
                if (!tailOnLeft) {
                    lineTo(box.right, midY - halfTail)
                    lineTo(size.width, midY)
                    lineTo(box.right, midY + halfTail)
                }
                lineTo(box.right, box.bottom - radius)
                arcTo(Rect(Offset(box.right - radius, box.bottom - radius), radius), 0f, QUARTER_TURN, false)
                lineTo(box.left + radius, box.bottom)
                arcTo(Rect(Offset(box.left + radius, box.bottom - radius), radius), QUARTER_TURN, QUARTER_TURN, false)
                if (tailOnLeft) {
                    lineTo(box.left, midY + halfTail)
                    lineTo(0f, midY)
                    lineTo(box.left, midY - halfTail)
                }
                lineTo(box.left, box.top + radius)
                arcTo(Rect(Offset(box.left + radius, box.top + radius), radius), 2 * QUARTER_TURN, QUARTER_TURN, false)
                close()
            }
        return Outline.Generic(path)
    }

    companion object {
        private const val QUARTER_TURN = 90f

        val CORNER_RADIUS = 4.dp
        val TAIL_DEPTH = 6.dp
        val TAIL_HEIGHT = 12.dp

        /**
         * The centre of a one-line body (12dp padding around 20dp of text). Anchoring here keeps the
         * tail on the body of a quoted bubble instead of the seam with its quote band.
         */
        val TAIL_ANCHOR_FROM_BOTTOM = 22.dp
    }
}

/**
 * Fills the content with a bubble whose tail points to [tail], reserving the tail strip so the
 * content never sits in it.
 */
fun Modifier.zappBubble(tail: ZappBubbleShape.Tail, fill: Color): Modifier {
    val shape = ZappBubbleShape(tail)
    return background(fill, shape)
        .clip(shape)
        .padding(
            start = if (tail == ZappBubbleShape.Tail.LEADING) ZappBubbleShape.TAIL_DEPTH else 0.dp,
            end = if (tail == ZappBubbleShape.Tail.TRAILING) ZappBubbleShape.TAIL_DEPTH else 0.dp,
        )
}
