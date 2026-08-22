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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import kotlin.math.abs
import kotlin.math.floor

/**
 * The card, presented.
 *
 * Used at the three moments where a gift card is the subject rather than a row in a list: while the
 * sender's card is being funded, once it is ready to hand over, and when the recipient opens the
 * link. Someone opening a gift link is usually not a Zapp user yet, and this is the whole of their
 * first impression — a card turning on its own axis above its shadow. These are the screens in the
 * app allowed to be a moment rather than a form.
 *
 * One rule governs the turn everywhere: the card turns while something is in flight and settles
 * once it is done. It is never the thing reporting the wait — the progress bar underneath is,
 * and unlike a spinner it can say something true about how far along the work is.
 */

private const val FULL_CIRCLE = 360f
private const val HALF_CIRCLE = 180f
private const val QUARTER_CIRCLE = 90f
private const val THREE_QUARTER_CIRCLE = 270f

/** One unhurried revolution. Fast enough to read as alive, slow enough not to nag. */
private const val TURN_MS = 9_000

private const val BOB_MS = 2_600
private const val CAMERA_DISTANCE = 18f

/** Looking slightly down on the card, the way a thing on a plinth is looked at. */
private const val TILT_DEGREES = -7f

private val CORNER = 16.dp
private val CARD_MAX_WIDTH = 300.dp
private val BOB_TRAVEL = 7.dp

/** Concentric ovals standing in for a soft shadow — one blurred oval is not portable below API 31. */
private const val SHADOW_RINGS = 5
private const val SHADOW_ALPHA = 0.4f
private val SHADOW_WIDTH = 210.dp
private val SHADOW_HEIGHT = 22.dp

/** How much wider and weaker the shadow goes at the top of the float. */
private const val SHADOW_SPREAD_PER_LIFT = 0.16f
private const val SHADOW_FADE_PER_LIFT = 0.35f

@Composable
internal fun GiftCardPodium(
    amount: StringResource?,
    tier: GiftCardTier,
    isSettled: Boolean,
    modifier: Modifier = Modifier,
    caption: String? = null,
    fiat: StringResource? = null,
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

    LaunchedEffect(isSettled) {
        if (isSettled) {
            // Always at least a half turn before it locks, so settling reads as the reveal
            // finishing rather than as the animation being switched off mid-stride.
            var target = (floor(rotation.value / FULL_CIRCLE) + 1f) * FULL_CIRCLE
            if (target - rotation.value < HALF_CIRCLE) target += FULL_CIRCLE
            rotation.animateTo(
                targetValue = target,
                animationSpec = spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessVeryLow),
            )
        } else {
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

    BoxWithConstraints(
        modifier = modifier.fillMaxWidth().padding(vertical = 28.dp),
        contentAlignment = Alignment.Center,
    ) {
        val cardWidth = if (maxWidth < CARD_MAX_WIDTH) maxWidth else CARD_MAX_WIDTH

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier =
                    Modifier
                        .width(cardWidth)
                        .height(cardWidth / GIFT_CARD_ASPECT)
                        .graphicsLayer {
                            rotationY = rotation.value
                            rotationX = TILT_DEGREES
                            cameraDistance = CAMERA_DISTANCE * density
                            translationY = -BOB_TRAVEL.toPx() * bob
                        },
            ) {
                if (showBack) PodiumBack(stock) else PodiumFace(amount, caption, stock)
            }
            // Tighter and darker as the card comes down, the way a real one would be.
            PodiumShadow(lift = bob, modifier = Modifier.padding(top = 4.dp))
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
private fun PodiumFace(amount: StringResource?, caption: String?, stock: ZappGiftCardStock) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(CORNER))
                .background(stock.face)
                .border(1.dp, stock.edge, RoundedCornerShape(CORNER))
                .padding(horizontal = 22.dp, vertical = 20.dp),
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
        BasicText(
            text = caption?.uppercase().orEmpty(),
            style = ZappTheme.typography.groupLabel.copy(color = stock.inkFaint),
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
                .border(1.dp, stock.edge, RoundedCornerShape(CORNER))
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

@Composable
private fun PodiumShadow(lift: Float, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(width = SHADOW_WIDTH, height = SHADOW_HEIGHT)) {
        // A lifted card throws a wider, weaker shadow; a settled one a tighter, darker one.
        val spread = 1f + lift * SHADOW_SPREAD_PER_LIFT
        val strength = SHADOW_ALPHA * (1f - lift * SHADOW_FADE_PER_LIFT)
        repeat(SHADOW_RINGS) { ring ->
            val step = (ring + 1f) / SHADOW_RINGS
            val w = size.width * step * spread
            val h = size.height * step * spread
            drawOval(
                color = Color.Black.copy(alpha = strength / SHADOW_RINGS),
                topLeft = Offset((size.width - w) / 2f, (size.height - h) / 2f),
                size = Size(w, abs(h)),
            )
        }
    }
}
