// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.gift

import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.theme.colors.ZappGiftCardStock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * How much of a card behind stays in sight.
 *
 * The card underneath is drawn at full size and clipped, exactly as a wallet shows it — so this
 * slice is the real top of the real card, not a summary row standing in for one.
 *
 * Sized so the peek line clears the next card lapping over it: [DECK_OVERLAP] of this is hidden,
 * and what is left has to hold the amount and the status pill with room to read.
 */
private val PEEK_HEIGHT = 92.dp

/** Cards have round corners. This is most of what separates a card from a rectangle. */
internal val GIFT_CARD_CORNER = 16.dp

private val RESTING_LIFT = 8.dp
private val FRONT_LIFT = 22.dp

/** Card stock is about 0.76mm. This is the on-screen equivalent, exaggerated to read. */
private val CARD_THICKNESS = 3.dp

private const val HALF_TURN = 90f
internal const val FULL_TURN = 180f

/** Perspective for the turn. Lower is more extreme; under about 8 the face visibly warps. */
private const val CAMERA_DISTANCE = 14f

/** Half a pixel down, so the hairline sits inside the edge rather than straddling it. */
private const val SHEEN_CENTRE = 0.5f

/** Loose enough to feel like weight turning over, damped enough not to wobble at the end. */
private val TURN_SPEC = spring<Float>(dampingRatio = 0.72f, stiffness = Spring.StiffnessLow)

/**
 * One gift card, drawn as a card.
 *
 * Nothing here can lean on a recipient: a card is a bearer link handed to whoever the sender likes,
 * so it has no name to show. Identity comes from the denomination, the stock it is printed on and
 * the date it was made — which is also what makes a stack of them readable at a glance.
 *
 * Everything a sender can do to a card lives on the card: the hand-off, the check, and the check's
 * progress. Nothing about it is announced in text underneath, because a card you can act on
 * directly is an object and a card with a column of buttons beneath it is a list row wearing one.
 */
@Composable
internal fun GiftDeckCard(
    item: GiftCardListItem,
    isExpanded: Boolean,
    isFlipped: Boolean,
    onSelect: () -> Unit,
    onFlip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val stock = item.tier.stock()
    val scope = rememberCoroutineScope()
    val rotation = remember { Animatable(0f) }
    val showBack by remember { derivedStateOf { rotation.value > HALF_TURN } }
    val shape = RoundedCornerShape(GIFT_CARD_CORNER)

    // A collapsed card is never mid-turn: it snaps flat so a card leaving the front of the stack
    // slides under the next one rather than pirouetting on its way out.
    LaunchedEffect(isExpanded, isFlipped) {
        if (!isExpanded) {
            rotation.snapTo(0f)
        } else {
            rotation.animateTo(if (isFlipped) FULL_TURN else 0f, TURN_SPEC)
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val fullHeight = maxWidth / GIFT_CARD_ASPECT
        val height by
            animateDpAsState(
                targetValue = if (isExpanded) fullHeight else PEEK_HEIGHT,
                animationSpec =
                    spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                label = "giftCardHeight",
            )
        val lift by
            animateDpAsState(
                targetValue = if (isExpanded) FRONT_LIFT else RESTING_LIFT,
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                label = "giftCardLift",
            )
        val width = constraints.maxWidth.toFloat()

        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(height)
                    .cardTurn(rotation)
                    // Pure black on both, because the default ambient grey all but vanishes on a
                    // near-black page — and the shadow is the whole reason the stack has depth.
                    .shadow(
                        elevation = lift,
                        shape = shape,
                        clip = false,
                        ambientColor = Color.Black,
                        spotColor = Color.Black,
                    ).clip(shape)
                    .background(stock.face)
                    .border(stock.edgeWidth, stock.edge, shape)
                    .topSheen(stock)
                    .clickable(
                        onClickLabel = stringResource(cardActionLabel(isExpanded, isFlipped)),
                        onClick = if (isExpanded) onFlip else onSelect,
                    )
                    // Swiping is the gesture the card invites; the click above is what makes the
                    // same move reachable without one.
                    .flipOnSwipe(
                        isEnabled = isExpanded,
                        isFlipped = isFlipped,
                        width = width,
                        rotation = rotation,
                        scope = scope,
                        onFlip = onFlip,
                    ),
        ) {
            if (isExpanded || showBack) {
                // Laid out at full size whatever the box is showing. Required, not plain height:
                // the parent's constraints would otherwise squash the face into the strip.
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.TopStart)
                            .fillMaxWidth()
                            .requiredHeight(fullHeight),
                ) {
                    // Only under the front. The back counter-rotates its content, and a flare drawn
                    // for both faces came out mirrored on the reverse.
                    if (showBack) {
                        CardBack(item, stock)
                    } else {
                        CardFlare(stock, GIFT_CARD_CORNER)
                        CardFront(item, stock)
                    }
                }
            } else {
                // A stacked card draws its own strip rather than a clipped copy of the open face.
                // Clipping was meant to reveal the face's top row and revealed the one below it,
                // so the deck read out in fiat instead of ZEC.
                CardFlare(stock, GIFT_CARD_CORNER)
                CardPeek(item, stock)
            }
            // The card seen edge-on along its own bottom. Only on the open card: on a peeking one
            // that line is where the next card laps over, not where this one ends.
            if (isExpanded) {
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(CARD_THICKNESS)
                            .background(stock.core),
                )
            }
            (item.check as? GiftCheckControl.Running)?.let {
                ScanTrack(
                    fraction = it.progress?.fraction,
                    stock = stock,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}

@StringRes
private fun cardActionLabel(isExpanded: Boolean, isFlipped: Boolean) =
    when {
        !isExpanded -> R.string.gift_card_deck_open
        isFlipped -> R.string.gift_card_deck_show_front
        else -> R.string.gift_card_deck_show_back
    }

/**
 * Drag the card round its own axis, settling to whichever face it was closest to on release.
 *
 * A cancel is not an end: the enclosing list can claim the pointer mid-swipe, and without settling
 * on that path the card is left frozen at a partial, perspective-warped angle.
 */
private fun Modifier.flipOnSwipe(
    isEnabled: Boolean,
    isFlipped: Boolean,
    width: Float,
    rotation: Animatable<Float, *>,
    scope: CoroutineScope,
    onFlip: () -> Unit,
): Modifier {
    if (!isEnabled) return this

    fun settle() = scope.launch { rotation.animateTo(if (isFlipped) FULL_TURN else 0f, TURN_SPEC) }

    return this.pointerInput(isFlipped, width) {
        detectHorizontalDragGestures(
            onDragCancel = { settle() },
            onDragEnd = { if ((rotation.value > HALF_TURN) != isFlipped) onFlip() else settle() },
            onHorizontalDrag = { _, dragAmount ->
                scope.launch {
                    val turned = rotation.value - dragAmount / width * FULL_TURN
                    rotation.snapTo(turned.coerceIn(0f, FULL_TURN))
                }
            },
        )
    }
}

/** The turn itself, plus the perspective that keeps it from reading as a flat squash. */
private fun Modifier.cardTurn(rotation: Animatable<Float, *>) =
    this.then(
        Modifier.graphicsLayer {
            rotationY = rotation.value
            cameraDistance = CAMERA_DISTANCE * density
        }
    )

/** A hairline of light along the top edge. A shadow alone gives a card depth; this gives it a lip. */
private fun Modifier.topSheen(stock: ZappGiftCardStock) =
    this.then(
        Modifier.drawWithContent {
            drawContent()
            val inset = GIFT_CARD_CORNER.toPx()
            val y = density * SHEEN_CENTRE
            drawLine(
                color = stock.sheen,
                start = Offset(inset, y),
                end = Offset(size.width - inset, y),
                strokeWidth = density,
            )
        }
    )
