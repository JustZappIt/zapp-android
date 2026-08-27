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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.theme.colors.ZappGiftCardStock
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.getValue
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

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
private const val CAMERA_DISTANCE_WIDTH_FACTOR = 1.7f

private val CORNER = 16.dp
private val BOB_TRAVEL = 6.dp

/** Matches the open card in the deck, so the same object is the same size everywhere. */
private val CARD_MAX_WIDTH = 420.dp
private val GUTTER = 16.dp
private val VERTICAL_ROOM = 20.dp
private val AMBIENT_LIFT = 24.dp
private val CONTACT_LIFT = 7.dp
private val AMBIENT_SHADOW_DROP = 9.dp
private val CONTACT_SHADOW_DROP = 4.dp

/** Card stock is about 0.76mm. This is the on-screen equivalent, exaggerated to read. */
private val CARD_THICKNESS = 6.dp
private const val CARD_FACE_DEPTH = 0.5f
private const val SHADOW_BOB_RATIO = 0.18f
private const val SHADOW_MIN_WIDTH_FRACTION = 0.02f

/** Light across the face stays quiet when flat and becomes more apparent as the card turns. */
private const val LIGHT_BASE = 0.025f
private const val LIGHT_SHEEN_WEIGHT = 0.16f
private const val LIGHT_GLANCING_GAIN = 0.09f
private const val SHADE_BASE = 0.035f
private const val SHADE_GLANCING_GAIN = 0.11f
private const val LIGHT_MIDPOINT = 0.42f
private const val SHADE_MIDPOINT = 0.7f
private const val EDGE_SHEEN_ALPHA = 0.68f
private const val EDGE_SHEEN_Y = 0.5f

/** Enough of a note to be worth reading at a glance without crowding the denomination. */
private const val MESSAGE_LINES = 2

@Composable
internal fun GiftCardPodium(
    amount: StringResource?,
    tier: GiftCardTier,
    isSettled: Boolean,
    modifier: Modifier = Modifier,
    /** The same worth in the wallet's chosen currency, under the ZEC figure. Null when no rate. */
    fiat: StringResource? = null,
    caption: String? = null,
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
    val bob =
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
    // One plane per physical pixel keeps the edge solid at 90 degrees. Every plane uses the exact
    // same RenderNode transform, so it cannot drift away from the face or stair-step its corners.
    val coreLayerCount = with(LocalDensity.current) { CARD_THICKNESS.roundToPx().coerceAtLeast(1) + 1 }
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
                        .clickable(
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
                // The broad shadow stays nearer the surface than the bobbing card. That separation
                // is the visual cue that this is an object suspended over the page, rather than a
                // dark glow painted onto the card itself.
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .shadowProjection(
                                rotation = rotation,
                                bob = bob,
                                yOffset = AMBIENT_SHADOW_DROP,
                                bobRatio = SHADOW_BOB_RATIO,
                            ).shadow(
                                elevation = AMBIENT_LIFT,
                                shape = shape,
                                clip = false,
                                ambientColor = Color.Black,
                                spotColor = Color.Black,
                            ).background(Color.Black.copy(alpha = LIGHT_BASE), shape),
                )
                // A tighter shadow anchors the lower edge while the larger one supplies the soft
                // falloff. One shadow cannot convincingly do both jobs at phone scale.
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .shadowProjection(
                                rotation = rotation,
                                bob = bob,
                                yOffset = CONTACT_SHADOW_DROP,
                                bobRatio = SHADOW_BOB_RATIO,
                            ).shadow(
                                elevation = CONTACT_LIFT,
                                shape = shape,
                                clip = false,
                                ambientColor = Color.Black,
                                spotColor = Color.Black,
                            ).background(Color.Black.copy(alpha = SHADE_BASE), shape),
                )

                repeat(coreLayerCount) { layer ->
                    val fraction = layer.toFloat() / (coreLayerCount - 1)
                    // Far surface first, near surface last. The order reverses with the visible
                    // face so the core never paints over the physical front of the slab.
                    val depth = if (showBack) CARD_FACE_DEPTH - fraction else -CARD_FACE_DEPTH + fraction
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .cardPlaneTransform(rotation = rotation, bob = bob, depth = depth)
                                .clip(shape)
                                .background(stock.core),
                    )
                }

                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .cardPlaneTransform(
                                rotation = rotation,
                                bob = bob,
                                depth = if (showBack) -CARD_FACE_DEPTH else CARD_FACE_DEPTH,
                            ).clip(shape)
                            .materialLighting(rotation = rotation, stock = stock),
                ) {
                    if (showBack) PodiumBack(stock) else PodiumFace(amount, fiat, caption, message, stock)
                }
            }
            // Stated under the card as well as printed on it: the face is turned away half the
            // time, and what a gift is worth should not come and go with the rotation. In ZEC
            // rather than fiat, because ZEC is what the card actually carries — the fiat figure
            // is a conversion that drifts with the rate while the gift itself does not.
            amount?.let {
                BasicText(
                    text = it.getValue(),
                    style = ZappTheme.typography.displaySecondary.copy(color = ZappTheme.colors.text),
                    modifier = Modifier.padding(top = 14.dp),
                )
            }
        }
    }
}

/**
 * Keeps the shadow on the screen plane. Its caster narrows with the broad face but never collapses
 * past the projected card stock, so the object stays grounded while it is exactly edge-on.
 */
private fun Modifier.shadowProjection(
    rotation: Animatable<Float, *>,
    bob: State<Float>,
    yOffset: Dp = 0.dp,
    bobRatio: Float = 1f,
) = graphicsLayer {
    val radians = Math.toRadians(rotation.value.toDouble())
    val turn = sin(radians).toFloat()
    val faceWidth = abs(cos(radians)).toFloat()
    val edgeWidth = CARD_THICKNESS.toPx() / size.width * abs(turn)
    scaleX = (faceWidth + edgeWidth).coerceIn(SHADOW_MIN_WIDTH_FRACTION, 1f)
    translationY = yOffset.toPx() - BOB_TRAVEL.toPx() * bob.value * bobRatio
}

/**
 * Projects one plane in a centred slab. Depth becomes horizontal displacement as the card turns;
 * there is deliberately no depth-derived vertical offset, which was what broke the corner match.
 */
private fun Modifier.cardPlaneTransform(
    rotation: Animatable<Float, *>,
    bob: State<Float>,
    depth: Float,
) = graphicsLayer {
    val radians = Math.toRadians(rotation.value.toDouble())
    val turn = sin(radians).toFloat()
    rotationY = rotation.value
    cameraDistance = size.width * CAMERA_DISTANCE_WIDTH_FACTOR
    translationX = turn * CARD_THICKNESS.toPx() * depth
    translationY = -BOB_TRAVEL.toPx() * bob.value
}

/** A restrained moving highlight and falloff, both derived from the card-stock palette. */
private fun Modifier.materialLighting(rotation: Animatable<Float, *>, stock: ZappGiftCardStock) =
    drawWithContent {
        drawContent()
        val radians = Math.toRadians(rotation.value.toDouble())
        val glancing = abs(sin(radians)).toFloat()
        val light =
            stock.sheen.copy(
                alpha =
                    (LIGHT_BASE + stock.sheen.alpha * LIGHT_SHEEN_WEIGHT + glancing * LIGHT_GLANCING_GAIN)
                        .coerceAtMost(1f)
            )
        val shade = stock.core.copy(alpha = SHADE_BASE + glancing * SHADE_GLANCING_GAIN)
        // Reverse the local gradient on the mirrored back so light remains fixed at screen-left.
        val lighting =
            if (cos(radians) >= 0f) {
                Brush.horizontalGradient(
                    0f to light,
                    LIGHT_MIDPOINT to Color.Transparent,
                    SHADE_MIDPOINT to Color.Transparent,
                    1f to shade,
                )
            } else {
                Brush.horizontalGradient(
                    0f to shade,
                    LIGHT_MIDPOINT to Color.Transparent,
                    SHADE_MIDPOINT to Color.Transparent,
                    1f to light,
                )
            }
        drawRect(brush = lighting)
        drawLine(
            color = stock.sheen.copy(alpha = stock.sheen.alpha * EDGE_SHEEN_ALPHA),
            start = Offset(CORNER.toPx(), EDGE_SHEEN_Y.dp.toPx()),
            end = Offset(size.width - CORNER.toPx(), EDGE_SHEEN_Y.dp.toPx()),
            strokeWidth = density,
        )
    }

@Composable
private fun PodiumFace(
    amount: StringResource?,
    fiat: StringResource?,
    caption: String?,
    message: String?,
    stock: ZappGiftCardStock,
) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(stock.face)
                .border(stock.edgeWidth, stock.edge, RoundedCornerShape(CORNER)),
    ) {
        CardFlare(stock, CORNER)
        PodiumFaceContent(amount, fiat, caption, message, stock)
    }
}

@Composable
private fun PodiumFaceContent(
    amount: StringResource?,
    fiat: StringResource?,
    caption: String?,
    message: String?,
    stock: ZappGiftCardStock,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        // Absent on the stocks that carry a design of their own — see showsWordmark. The Column
        // below still needs something above it for SpaceBetween to push against, so the slot stays.
        if (stock.showsWordmark) {
            BasicText(
                text = stringResource(R.string.gift_card_deck_wordmark),
                style = ZappTheme.typography.groupLabel.copy(color = stock.inkMuted),
            )
        } else {
            Spacer(modifier = Modifier)
        }
        Column {
            BasicText(
                text = amount?.getValue().orEmpty(),
                style = ZappTheme.typography.display.copy(color = stock.figureInk ?: stock.ink),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            fiat?.let {
                BasicText(
                    text = it.getValue(),
                    style = ZappTheme.typography.caption.copy(color = stock.inkMuted),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
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
                .background(stock.face)
                .border(stock.edgeWidth, stock.edge, RoundedCornerShape(CORNER))
                .padding(18.dp),
        contentAlignment = Alignment.Center,
    ) {
        // The reverse is one framed field and nothing else, so the frame is the whole design — and
        // it is drawn in the stock's own ring rather than its edge, which makes the back of a card
        // recognisably the same card as the front without repeating a single word of it.
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .border(1.dp, stock.ring ?: stock.edge, RoundedCornerShape(CORNER - 8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (stock.showsWordmark) {
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
}
