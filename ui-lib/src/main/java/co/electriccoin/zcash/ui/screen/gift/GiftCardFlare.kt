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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.design.theme.colors.CardMotif
import co.electriccoin.zcash.ui.design.theme.colors.ZappGiftCardStock
import kotlin.math.cos
import kotlin.math.sin

/**
 * What a card picks up as its denomination climbs.
 *
 * Struck into the face rather than laid on top of it: everything here is drawn at low
 * opacity, so it reads as something done to the material rather than a graphic sitting on it. Read
 * the amount and you learn what the card is worth; glance at it and you already knew.
 *
 * Drawn outside in: the ring is the field's boundary, then whichever motif the stock carries. One
 * only — the Zapp Z is itself a motif here, so a card that has a design of its own cannot also be
 * stamped with the logo. [CardMotif] makes that a fact about the type rather than a rule someone
 * has to remember.
 */
@Composable
internal fun CardFlare(stock: ZappGiftCardStock, corner: Dp, modifier: Modifier = Modifier) {
    if (stock.isBare) return

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
        when (val motif = stock.motif) {
            is CardMotif.Mark -> drawMark(motif.ink, corner)
            is CardMotif.Sweep -> drawDiagonals(motif.ink, SWEEP_STROKES, SWEEP_WIDTH)
            is CardMotif.Claw -> drawDiagonals(motif.ink, CLAW_STROKES, CLAW_WIDTH)
            is CardMotif.Rosette -> drawRosette(motif.ink)
            is CardMotif.Scales -> drawScales(motif.ink)
            null -> Unit
        }
    }
}

/** Nothing to strike into this face: the plain end of the ladder, where the stock is the whole card. */
private val ZappGiftCardStock.isBare: Boolean
    get() = motif == null && ring == null

/**
 * The splash Z's accent diagonals, in card terms.
 *
 * [DIAGONAL_RUN] is how far a stroke travels sideways over the full height — taken from the splash
 * paths, which all run parallel at that ratio — so both the sweep and the claw keep the brand's
 * angle instead of defaulting to 45 degrees. Every stroke runs edge to edge: a diagonal that stops
 * short is a scuff, and what makes a claw mark read is that whatever left it did not slow down.
 *
 * What separates the two registers is only count and weight — two thin strokes are a sheen across
 * the face, three heavy ones are a tear through it.
 */
private fun DrawScope.drawDiagonals(ink: Color, count: Int, width: Float) {
    val run = size.height * DIAGONAL_RUN
    val gap = size.width * DIAGONAL_GAP
    val stroke = size.height * width
    repeat(count) { stripe ->
        val head = size.width * DIAGONAL_LEAD + gap * stripe
        drawLine(
            color = ink,
            start = Offset(head, 0f),
            end = Offset(head - run, size.height),
            strokeWidth = stroke,
        )
    }
}

/**
 * The Zapp Z, struck into the bottom corner — what a card wears when it has no design of its own.
 *
 * Drawn rather than set: a text glyph would inherit whatever face the system feels like and change
 * shape between devices, and this has to be the same mark on every card that carries it.
 */
private fun DrawScope.drawMark(ink: Color, corner: Dp) {
    val height = size.height * MARK_SCALE
    val pad = corner.toPx() + MARK_PAD.dp.toPx()
    val bottomRight = Offset(size.width - pad, size.height - pad)
    val width = height * MARK_ASPECT
    val left = bottomRight.x - width
    val top = bottomRight.y - height
    val path =
        Path().apply {
            moveTo(left, top)
            lineTo(bottomRight.x, top)
            lineTo(left, bottomRight.y)
            lineTo(bottomRight.x, bottomRight.y)
        }
    drawPath(path = path, color = ink, style = Stroke(width = height * MARK_STROKE))
}

/** A hypotrochoid — the spirograph curve behind the numerals on a banknote. */
private fun DrawScope.drawRosette(ink: Color) {
    val radius = size.minDimension * ROSETTE_SCALE
    val centre = Offset(size.width - radius * ROSETTE_INSET, size.height / 2f)
    val scale = radius / (OUTER - INNER + OFFSET)
    val path =
        Path().apply {
            for (step in 0..STEPS) {
                val t = step * STEP_RADIANS
                val k = (OUTER - INNER) / INNER
                val x = centre.x + ((OUTER - INNER) * cos(t) + OFFSET * cos(k * t)) * scale
                val y = centre.y + ((OUTER - INNER) * sin(t) - OFFSET * sin(k * t)) * scale
                if (step == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
    drawPath(path = path, color = ink, style = Stroke(width = ENGRAVING_WIDTH.dp.toPx()))
}

/**
 * A field of interlocking scales, filling the same corner the rosette occupies.
 *
 * Each scale is the lower half of a circle, and the rows overlap by half a scale so every arc tucks
 * under the two above it — which is the whole trick: drawn as separate arcs they are fish tiles,
 * drawn overlapping they are hide. Rows alternate by half a scale sideways so the seams never line
 * up into columns.
 */
private fun DrawScope.drawScales(ink: Color) {
    val scale = size.height * SCALE_SIZE
    val left = size.width * SCALE_FIELD
    val stroke = Stroke(width = SCALE_WIDTH.dp.toPx())
    val rows = ((size.height / (scale * SCALE_ROW)) + 2).toInt()
    val columns = (((size.width - left) / scale) + 2).toInt()
    clipRect(left = left) {
        repeat(rows) { row ->
            val y = row * scale * SCALE_ROW - scale
            val stagger = if (row % 2 == 0) 0f else scale / 2f
            repeat(columns) { column ->
                drawArc(
                    color = ink,
                    startAngle = 0f,
                    sweepAngle = HALF_CIRCLE,
                    useCenter = false,
                    topLeft = Offset(left - scale + stagger + column * scale, y),
                    size = Size(scale, scale),
                    style = stroke,
                )
            }
        }
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

private const val DIAGONAL_RUN = 0.4912f
private const val DIAGONAL_LEAD = 0.58f
private const val DIAGONAL_GAP = 0.11f

/** Two thin strokes. A single diagonal is a scratch; the pair is what reads as the mark. */
private const val SWEEP_STROKES = 2
private const val SWEEP_WIDTH = 0.018f

/** Three of the same strokes at three times the weight, which is the whole difference. */
private const val CLAW_STROKES = 3
private const val CLAW_WIDTH = 0.055f

private const val SCALE_SIZE = 0.19f
private const val SCALE_ROW = 0.52f
private const val SCALE_FIELD = 0.46f
private const val SCALE_WIDTH = 0.7f
private const val HALF_CIRCLE = 180f

/** Set in far enough to clear the foil edge, and to sit outside the face's own text padding. */
private const val RING_INSET = 0.055f
private const val RING_WIDTH = 0.8f

private const val MARK_SCALE = 0.2f
private const val MARK_ASPECT = 0.78f
private const val MARK_STROKE = 0.14f
private const val MARK_PAD = 4f
