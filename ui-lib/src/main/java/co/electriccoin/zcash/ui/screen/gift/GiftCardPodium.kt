// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.gift

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.theme.colors.ZappGiftCardStock
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.getValue
import kotlin.math.roundToInt

/**
 * The card, presented.
 *
 * Used wherever a gift card is the subject rather than a row in a list: while the sender is still
 * deciding what to put on it, while it is being funded, once it is ready to hand over, and when the
 * recipient opens the link. Someone opening a gift link is usually not a Zapp user yet, and this is
 * the whole of their first impression. These are the screens in the app allowed to be a moment
 * rather than a form.
 *
 * One rule governs the turn: the card turns by itself only while something is in flight, and is
 * still the rest of the time. It is never what reports a wait — the progress bar underneath is, and
 * unlike a spinner it can say something true about how far along the work is. While the card is
 * still, a tap turns it over onto its other face, exactly as a card in the deck does.
 */

private const val FULL_CIRCLE = 360f
private const val HALF_CIRCLE = 180f
private const val QUARTER_CIRCLE = 90f
private const val THREE_QUARTER_CIRCLE = 270f

/** One unhurried revolution. Fast enough to read as alive, slow enough not to nag. */
private const val TURN_MS = 9_000

private const val BOB_MS = 2_600

/** A flourish is a whole turn, so it reads as a different gesture from a tap's single face. */
private const val HALF_TURNS_PER_CIRCLE = 2

private val SETTLE_SPEC = spring<Float>(dampingRatio = 0.78f, stiffness = Spring.StiffnessLow)
private const val CAMERA_DISTANCE = 18f

private val CORNER = 16.dp
private val BOB_TRAVEL = 6.dp

/** Matches the open card in the deck, so the same object is the same size everywhere. */
private val CARD_MAX_WIDTH = 420.dp
private val GUTTER = 16.dp
private val VERTICAL_ROOM = 20.dp
private val LIFT = 22.dp

/** Card stock is about 0.76mm. This is the on-screen equivalent, exaggerated to read. */
private val CARD_THICKNESS = 3.dp

/** Enough of a note to be worth reading at a glance without crowding the denomination. */
private const val MESSAGE_LINES = 2

@Composable
internal fun GiftCardPodium(
    amount: StringResource?,
    tier: GiftCardTier,
    isSettled: Boolean,
    modifier: Modifier = Modifier,
    caption: String? = null,
    fiat: StringResource? = null,
    /** Shown on the face, so the note lands on the card rather than in a row beneath it. */
    message: String? = null,
    /** Changing this turns the card once. Used to mark a denomination crossing onto better stock. */
    flourishOn: Any? = null,
) {
    val stock = tier.stock()
    val rotation = remember { Animatable(0f) }
    // Derived, so only crossing an edge invalidates. Reading the Animatable straight from the
    // content lambda recomposed the whole podium on every animation frame, forever.
    val showBack by
        remember {
            derivedStateOf {
                val facing = ((rotation.value % FULL_CIRCLE) + FULL_CIRCLE) % FULL_CIRCLE
                facing in QUARTER_CIRCLE..THREE_QUARTER_CIRCLE
            }
        }

    // Which face is up, counted in half turns. An absolute target rather than repeated relative
    // arithmetic: a second tap mid-turn retargets cleanly instead of compounding into an angle
    // where neither face is showing, and a flourish interrupted by another does the same.
    var halfTurns by remember { mutableIntStateOf(0) }

    // Skips the first pass, so arriving on the screen is not itself a flourish.
    var hasSeenFlourishKey by remember { mutableStateOf(false) }
    LaunchedEffect(flourishOn) {
        if (!hasSeenFlourishKey) hasSeenFlourishKey = true else halfTurns += HALF_TURNS_PER_CIRCLE
    }

    // Adopts whatever angle a free turn left behind, so the counter and the card agree before the
    // card is ever asked to hold a face.
    LaunchedEffect(isSettled) {
        if (isSettled) halfTurns = (rotation.value / HALF_CIRCLE).roundToInt()
    }

    LaunchedEffect(isSettled, halfTurns) {
        if (isSettled) {
            rotation.animateTo(halfTurns * HALF_CIRCLE, SETTLE_SPEC)
            return@LaunchedEffect
        }
        while (true) {
            rotation.animateTo(
                targetValue = rotation.value + FULL_CIRCLE,
                animationSpec = tween(TURN_MS, easing = LinearEasing),
            )
            // Wound back to the same angle it just reached, so a screen left open does not
            // accumulate revolutions until a Float can no longer tell degrees apart.
            rotation.snapTo(rotation.value % FULL_CIRCLE)
        }
    }

    val float = rememberInfiniteTransition(label = "giftPodiumFloat")
    val bob by
        float.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(BOB_MS, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
            label = "giftPodiumBob",
        )

    val shape = RoundedCornerShape(CORNER)
    BoxWithConstraints(
        modifier = modifier.fillMaxWidth().padding(horizontal = GUTTER, vertical = VERTICAL_ROOM),
        contentAlignment = Alignment.Center,
    ) {
        // Capped well above any phone's content width, so phones are unaffected and a tablet
        // does not get a card tall enough to push the rest of the screen below the fold.
        val cardWidth = if (maxWidth < CARD_MAX_WIDTH) maxWidth else CARD_MAX_WIDTH

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier =
                    Modifier
                        .width(cardWidth)
                        .height(cardWidth / GIFT_CARD_ASPECT)
                        .graphicsLayer {
                            rotationY = rotation.value
                            cameraDistance = CAMERA_DISTANCE * density
                            translationY = -BOB_TRAVEL.toPx() * bob
                        }.shadow(
                            elevation = LIFT,
                            shape = shape,
                            clip = false,
                            ambientColor = Color.Black,
                            spotColor = Color.Black,
                        ).clickable(
                            enabled = isSettled,
                            onClickLabel =
                                stringResource(
                                    if (showBack) {
                                        R.string.gift_card_deck_show_front
                                    } else {
                                        R.string.gift_card_deck_show_back
                                    }
                                ),
                        ) {
                            // One face to the next, the same as tapping a card in the deck. Only
                            // while it is still: a card already turning has no face to turn from.
                            halfTurns += 1
                        },
            ) {
                // The slab the face is printed on, peeking past it. At the podium's tilt this is
                // the edge of the card, and it is what stops the whole thing reading as a decal.
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .offset(y = CARD_THICKNESS)
                            .clip(RoundedCornerShape(CORNER))
                            .background(stock.core),
                )
                if (showBack) PodiumBack(stock) else PodiumFace(amount, caption, message, stock)
            }
            // Stated under the card as well as printed on it: the face is turned away half the
            // time, and what a gift is worth should not come and go with the rotation.
            fiat?.let {
                BasicText(
                    text = it.getValue(),
                    style = ZappTheme.typography.displaySecondary.copy(color = ZappTheme.colors.text),
                    modifier = Modifier.padding(top = 14.dp),
                )
            }
        }
    }
}

@Composable
private fun PodiumFace(
    amount: StringResource?,
    caption: String?,
    message: String?,
    stock: ZappGiftCardStock,
) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(CORNER))
                .background(stock.face)
                .border(stock.edgeWidth, stock.edge, RoundedCornerShape(CORNER)),
    ) {
        CardFlare(stock, CORNER)
        PodiumFaceContent(amount, caption, message, stock)
    }
}

@Composable
private fun PodiumFaceContent(
    amount: StringResource?,
    caption: String?,
    message: String?,
    stock: ZappGiftCardStock,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        BasicText(
            text = stringResource(R.string.gift_card_deck_wordmark),
            style = ZappTheme.typography.groupLabel.copy(color = stock.inkMuted),
        )
        BasicText(
            text = amount?.getValue().orEmpty(),
            style = ZappTheme.typography.display.copy(color = stock.ink),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // The note, where the sender wrote it to be read. Set in caption rather than the eyebrow
        // style, and in the middle ink rather than the faintest: this is prose, and the faint
        // small-caps treatment the caption uses is not legible at that length. The full text still
        // lives in a row beneath the card, because a note can run to 128 graphemes.
        val note = message?.trim()?.takeIf { it.isNotEmpty() }
        BasicText(
            text = note ?: caption?.uppercase().orEmpty(),
            maxLines = MESSAGE_LINES,
            overflow = TextOverflow.Ellipsis,
            style =
                if (note == null) {
                    ZappTheme.typography.groupLabel.copy(color = stock.inkFaint)
                } else {
                    ZappTheme.typography.caption.copy(color = stock.inkMuted)
                },
        )
    }
}

/** The reverse: a plain face with the mark centred, mirrored back the right way round by the turn. */
@Composable
private fun PodiumBack(stock: ZappGiftCardStock) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .graphicsLayer { rotationY = HALF_CIRCLE }
                .clip(RoundedCornerShape(CORNER))
                .background(stock.face)
                .border(stock.edgeWidth, stock.edge, RoundedCornerShape(CORNER))
                .padding(18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .border(1.dp, stock.edge, RoundedCornerShape(CORNER - 8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text = stringResource(R.string.gift_card_deck_wordmark).uppercase(),
                style =
                    ZappTheme.typography.eyebrow.copy(
                        color = stock.inkMuted,
                        textAlign = TextAlign.Center,
                    ),
            )
        }
    }
}
