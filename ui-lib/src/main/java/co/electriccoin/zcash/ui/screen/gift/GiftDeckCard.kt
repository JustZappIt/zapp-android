// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.gift

import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.theme.colors.ZappGiftCardStock
import co.electriccoin.zcash.ui.design.theme.colors.ZappGiftCardStocks
import co.electriccoin.zcash.ui.design.util.getValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * How much of a card behind stays in sight.
 *
 * The card underneath is drawn at full size and clipped, exactly as a wallet shows it — so this
 * slice is the real top of the real card, not a summary row standing in for one.
 */
private val PEEK_HEIGHT = 78.dp

/** Cards have round corners. This is most of what separates a card from a rectangle. */
internal val GIFT_CARD_CORNER = 16.dp

private val RESTING_LIFT = 8.dp
private val FRONT_LIFT = 22.dp

private const val HALF_TURN = 90f
private const val FULL_TURN = 180f

/** Perspective for the turn. Lower is more extreme; under about 8 the face visibly warps. */
private const val CAMERA_DISTANCE = 14f

private val TRACK_HEIGHT = 3.dp
private const val SWEEP_WIDTH = 0.3f
private const val SWEEP_MS = 1_100

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
                    .border(1.dp, stock.edge, shape)
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
            // Laid out at full size whatever the box is showing, so a peeking card is genuinely the
            // top of that card rather than a second, shorter design of it. Required, not plain
            // height: the parent's constraints would otherwise squash the face into the strip.
            Box(
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .requiredHeight(fullHeight),
            ) {
                if (showBack) CardBack(item, stock) else CardFront(item, stock)
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

@Composable
private fun CardFront(item: GiftCardListItem, stock: ZappGiftCardStock) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        // Everything in this row has to survive being the only part of the card in sight.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                BasicText(
                    text = item.amount.getValue(),
                    style = ZappTheme.typography.displaySecondary.copy(color = stock.ink),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag(GiftCardListTag.AMOUNT),
                )
                // Absent whenever the wallet has no rate to show — never a zero, which would read
                // as a card worth nothing.
                item.fiat?.let {
                    BasicText(
                        text = it.getValue(),
                        style = ZappTheme.typography.body.copy(color = stock.inkMuted),
                        maxLines = 1,
                    )
                }
            }
            StatusPill(item.status, stock)
        }

        item.message?.let {
            BasicText(
                text = it,
                style = ZappTheme.typography.body.copy(color = stock.inkMuted),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                BasicText(
                    text = stringResource(R.string.gift_card_deck_wordmark),
                    style = ZappTheme.typography.groupLabel.copy(color = stock.inkMuted),
                )
                item.createdAt?.let {
                    BasicText(
                        text = it.getValue(),
                        style = ZappTheme.typography.caption.copy(color = stock.inkFaint),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            CardTools(item, stock)
        }
    }
}

/**
 * The card's own controls.
 *
 * Icons rather than a stack of full-width buttons: three sentences of chrome under a card is more
 * furniture than the card itself, and every one of these is a verb the sender already understands.
 * Copy sits beside Share because it is the only hand-off that reports its own outcome — the chooser
 * marks a card handed out only if the system says a target was picked, and a card wrongly counted
 * as unshared goes on blocking a wallet reset.
 */
@Composable
private fun CardTools(item: GiftCardListItem, stock: ZappGiftCardStock) {
    val sharePickerText = stringResource(R.string.gift_card_list_share_picker)
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        item.handOff?.let { handOff ->
            CardTool(
                icon = Icons.Default.ContentCopy,
                label = stringResource(R.string.gift_card_list_copy),
                stock = stock,
                onClick = handOff.onCopy,
            )
            CardTool(
                icon = Icons.Default.Share,
                label = stringResource(R.string.gift_card_list_share),
                stock = stock,
                onClick = { handOff.onShare(sharePickerText) },
            )
        }
        when (val check = item.check) {
            is GiftCheckControl.Ready -> {
                CardTool(
                    icon = Icons.Default.Refresh,
                    label = stringResource(R.string.gift_card_list_check),
                    stock = stock,
                    onClick = check.onCheck,
                )
            }

            is GiftCheckControl.Running -> {
                CardTool(
                    icon = Icons.Default.Close,
                    label = stringResource(R.string.gift_card_list_check_stop),
                    stock = stock,
                    tint = ZappGiftCardStocks.LiveMark,
                    onClick = check.onStop,
                )
            }

            // Shown flat and inert; the sentence saying why is on the back, where it fits.
            is GiftCheckControl.Blocked -> {
                CardTool(
                    icon = Icons.Default.Refresh,
                    label = stringResource(R.string.gift_card_list_check),
                    stock = stock,
                    isEnabled = false,
                    onClick = {},
                )
            }

            GiftCheckControl.Hidden -> {
                Unit
            }
        }
    }
}

@Composable
private fun CardTool(
    icon: ImageVector,
    label: String,
    stock: ZappGiftCardStock,
    onClick: () -> Unit,
    isEnabled: Boolean = true,
    tint: Color? = null,
) {
    Box(
        modifier =
            Modifier
                .size(TOOL_TARGET)
                .clip(CircleShape)
                .clickable(enabled = isEnabled, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (isEnabled) tint ?: stock.inkMuted else stock.inkFaint,
            modifier = Modifier.size(TOOL_ICON),
        )
    }
}

/**
 * The scan, drawn as the card filling up along its own bottom edge.
 *
 * A sweeping block rather than a bar sitting at zero for the stretch before the SDK measures
 * anything: a scan can legitimately run for minutes, and an honest "still working" beats a figure
 * that has not been earned.
 */
@Composable
private fun ScanTrack(fraction: Float?, stock: ZappGiftCardStock, modifier: Modifier = Modifier) {
    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxWidth()
                .height(TRACK_HEIGHT)
                .background(stock.edge),
    ) {
        if (fraction == null) {
            val transition = rememberInfiniteTransition(label = "giftScanSweep")
            val offset by
                transition.animateFloat(
                    initialValue = -SWEEP_WIDTH,
                    targetValue = 1f,
                    animationSpec =
                        infiniteRepeatable(
                            animation = tween(SWEEP_MS, easing = LinearEasing),
                            repeatMode = RepeatMode.Restart,
                        ),
                    label = "giftScanSweepOffset",
                )
            val trackWidthPx = constraints.maxWidth.toFloat()
            Box(
                modifier =
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(SWEEP_WIDTH)
                        // Against the track's own width. Measuring inside a layout block would read
                        // the width fillMaxWidth already narrowed to, stalling the sweep short.
                        .graphicsLayer { translationX = offset * trackWidthPx }
                        .background(ZappGiftCardStocks.LiveMark),
            )
        } else {
            val animated by
                animateFloatAsState(
                    targetValue = fraction.coerceIn(0f, 1f),
                    label = "giftScanFill",
                )
            Box(
                modifier =
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(animated)
                        .background(ZappGiftCardStocks.LiveMark),
            )
        }
    }
}

@Composable
private fun StatusPill(status: GiftCardListStatus, stock: ZappGiftCardStock) {
    val isSettled = status == GiftCardListStatus.CLAIMED
    val mark = if (isSettled) stock.inkFaint else ZappGiftCardStocks.LiveMark
    Row(
        modifier =
            Modifier
                .clip(CircleShape)
                .background(stock.ink.copy(alpha = PILL_TINT))
                .padding(horizontal = 10.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(mark))
        BasicText(
            text = stringResource(status.chipRes()),
            style = ZappTheme.typography.chip.copy(color = if (isSettled) stock.inkMuted else stock.ink),
            maxLines = 1,
        )
    }
}

@Composable
private fun CardBack(item: GiftCardListItem, stock: ZappGiftCardStock) {
    val caption = ZappTheme.typography.caption.copy(color = stock.inkFaint)
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                // The face is mirrored by the turn; this puts its content the right way round.
                .graphicsLayer { rotationY = FULL_TURN }
                .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BasicText(
            text = stringResource(R.string.gift_card_deck_details).uppercase(),
            style = ZappTheme.typography.groupLabel.copy(color = stock.inkFaint),
        )
        item.message?.let {
            BasicText(
                text = it,
                style = ZappTheme.typography.body.copy(color = stock.ink),
                maxLines = MESSAGE_LINES,
                overflow = TextOverflow.Ellipsis,
            )
        }
        item.createdAt?.let {
            BasicText(text = stringResource(R.string.gift_card_deck_created, it.getValue()), style = caption)
        }
        item.expiry?.let { expiry ->
            val label = if (expiry.isPast) R.string.gift_card_list_expired else R.string.gift_card_list_expires
            BasicText(text = stringResource(label, expiry.date.getValue()), style = caption)
        }
        item.lastCheckedAt?.let {
            BasicText(text = stringResource(R.string.gift_card_list_checked_unclaimed, it.getValue()), style = caption)
        }

        Spacer(modifier = Modifier.weight(1f))

        // The long form of the status lives here rather than on the front: the sentences that matter
        // most — an unresolved funding, a card that may already have cost the sender money — do not
        // fit in a pill, and the front is not the place to shout them.
        BasicText(
            text = stringResource(item.statusDetailRes()),
            style = ZappTheme.typography.caption.copy(color = stock.inkMuted),
            maxLines = DETAIL_LINES,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * What the back says about where the card stands.
 *
 * A blocked check outranks the status: "there is nothing to look for yet" is the answer to the
 * question the greyed-out control just raised, and the status is still readable on the front.
 */
private fun GiftCardListItem.statusDetailRes() =
    (check as? GiftCheckControl.Blocked)?.reason?.reasonRes() ?: status.labelRes()

private val TOOL_TARGET: Dp = 44.dp
private val TOOL_ICON: Dp = 19.dp
private const val PILL_TINT = 0.09f

/** Half a pixel down, so the hairline sits inside the edge rather than straddling it. */
private const val SHEEN_CENTRE = 0.5f
private const val MESSAGE_LINES = 3
private const val DETAIL_LINES = 3
