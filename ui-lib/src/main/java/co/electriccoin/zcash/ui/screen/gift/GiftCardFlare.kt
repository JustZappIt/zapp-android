// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.gift

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.design.theme.colors.ZappGiftCardStock
import kotlin.math.cos
import kotlin.math.sin

/**
 * What a card picks up as its denomination climbs.
 *
 * Struck into the face rather than laid on top of it: everything here is drawn at single-digit
 * opacity, so it reads as something done to the material rather than a graphic sitting on it. Read
 * the amount and you learn what the card is worth; glance at it and you already knew.
 *
 * Drawn outside in: the ring is the field's boundary, then whichever ornament register the stock
 * belongs to — the splash sweep in the ladder's middle, the guilloché at its top, never both —
 * and the mark last, sitting on everything.
 */
@Composable
internal fun CardFlare(stock: ZappGiftCardStock, corner: Dp, modifier: Modifier = Modifier) {
    if (stock.isBare) return
    // The two ornament registers are meant to be exclusive; a stock carrying both would read as
    // two cards printed on top of each other. Cheaper to catch here than in review.
    check(stock.spark == null || stock.engraving == null) {
        "A stock carries the sweep or the engraving, never both"
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        stock.ring?.let { ink ->
            // The field's own boundary, so it is drawn under everything that sits inside it.
            val inset = size.minDimension * RING_INSET
            val radius = (corner.toPx() - inset).coerceAtLeast(0f)
            drawRoundRect(
                color = ink,
                topLeft = Offset(inset, inset),
                size = Size(size.width - inset * 2f, size.height - inset * 2f),
                cornerRadius = CornerRadius(radius, radius),
                style = Stroke(width = RING_WIDTH.dp.toPx()),
            )
        }
        stock.spark?.let { ink ->
            // The splash Z's twin diagonals, carried over at the angle they run on the splash
            // screen so the two marks are recognisably the same gesture. Drawn first, under the
            // engraving and the mark: on the stocks that have all three these are layers of one
            // impression, not three graphics competing for the same face.
            val run = size.height * SPARK_RUN
            val lead = size.width * SPARK_LEAD
            val gap = size.width * SPARK_GAP
            val stroke = size.height * SPARK_WIDTH
            repeat(SPARK_STROKES) { stripe ->
                val x = lead + gap * stripe
                drawLine(
                    color = ink,
                    start = Offset(x, 0f),
                    end = Offset(x - run, size.height),
                    strokeWidth = stroke,
                )
            }
        }
        stock.engraving?.let { ink ->
            // A hypotrochoid — the spirograph curve behind the numerals on a banknote. Parametric
            // rather than a stored path: it costs a few hundred points and no asset.
            val radius = size.minDimension * ROSETTE_SCALE
            val path =
                guilloche(
                    centre = Offset(size.width - radius * ROSETTE_INSET, size.height / 2f),
                    radius = radius,
                )
            drawPath(path = path, color = ink, style = Stroke(width = ENGRAVING_WIDTH.dp.toPx()))
        }
        stock.watermark?.let { ink ->
            val height = size.height * MARK_SCALE
            val pad = corner.toPx() + MARK_PAD.dp.toPx()
            drawPath(
                path = zappMark(bottomRight = Offset(size.width - pad, size.height - pad), height = height),
                color = ink,
                style = Stroke(width = height * MARK_STROKE),
            )
        }
    }
}

/** Nothing to strike into this face: the plain end of the ladder, where the stock is the whole card. */
private val ZappGiftCardStock.isBare: Boolean
    get() = listOfNotNull(engraving, watermark, spark, ring).isEmpty()

/**
 * The Zapp Z, drawn rather than set: a text glyph would inherit whatever face the system feels like
 * and change shape between devices, and this has to be the same mark on every card.
 */
private fun zappMark(bottomRight: Offset, height: Float): Path {
    val width = height * MARK_ASPECT
    val left = bottomRight.x - width
    val top = bottomRight.y - height
    return Path().apply {
        moveTo(left, top)
        lineTo(bottomRight.x, top)
        lineTo(left, bottomRight.y)
        lineTo(bottomRight.x, bottomRight.y)
    }
}

private fun guilloche(centre: Offset, radius: Float): Path {
    val scale = radius / (OUTER - INNER + OFFSET)
    return Path().apply {
        for (step in 0..STEPS) {
            val t = step * STEP_RADIANS
            val k = (OUTER - INNER) / INNER
            val x = centre.x + ((OUTER - INNER) * cos(t) + OFFSET * cos(k * t)) * scale
            val y = centre.y + ((OUTER - INNER) * sin(t) - OFFSET * sin(k * t)) * scale
            if (step == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }
}

/** Three turns of a 7/3 rose: dense enough to read as engraving, open enough not to grey out. */
private const val OUTER = 7f
private const val INNER = 3f
private const val OFFSET = 5f
private const val STEPS = 270
private const val STEP_RADIANS = 0.0698f
private const val ROSETTE_SCALE = 0.62f
private const val ROSETTE_INSET = 0.75f
private const val ENGRAVING_WIDTH = 0.6f

/**
 * The splash Z's accent diagonals, in card terms. [SPARK_RUN] is how far a stroke travels sideways
 * over the full height — taken from the splash paths, which all run parallel at that ratio — so the
 * sweep keeps the brand's angle instead of defaulting to 45 degrees. Two strokes, because a single
 * diagonal is a scratch and the pair is what reads as the mark.
 */
private const val SPARK_RUN = 0.4912f
private const val SPARK_LEAD = 0.58f
private const val SPARK_GAP = 0.11f
private const val SPARK_WIDTH = 0.018f
private const val SPARK_STROKES = 2

/** Set in far enough to clear the foil edge, and to sit outside the face's own text padding. */
private const val RING_INSET = 0.055f
private const val RING_WIDTH = 0.8f

private const val MARK_SCALE = 0.2f
private const val MARK_ASPECT = 0.78f
private const val MARK_STROKE = 0.14f
private const val MARK_PAD = 4f
