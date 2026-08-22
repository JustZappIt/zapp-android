// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.gift

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
 */
@Composable
internal fun CardFlare(stock: ZappGiftCardStock, corner: Dp, modifier: Modifier = Modifier) {
    if (stock.engraving == null && stock.watermark == null) return

    Canvas(modifier = modifier.fillMaxSize()) {
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

private const val MARK_SCALE = 0.2f
private const val MARK_ASPECT = 0.78f
private const val MARK_STROKE = 0.14f
private const val MARK_PAD = 4f
