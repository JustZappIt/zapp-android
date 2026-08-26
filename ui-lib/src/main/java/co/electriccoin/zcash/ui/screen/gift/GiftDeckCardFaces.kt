// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.gift

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
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

// What is printed on a card in the deck. Split from GiftDeckCard, which owns the object — its
// shape, its shadow, and the gestures that turn it. This owns what the two faces say.

/** The bar a running scan fills along the card's bottom edge. */
private val TRACK_HEIGHT = 3.dp
private const val SWEEP_WIDTH = 0.3f
private const val PERCENT = 100
private const val SWEEP_MS = 1_100

/**
 * What a stacked card shows: its denomination, and whether it has been collected.
 *
 * Drawn in the collapsed card's own strip rather than clipped out of [CardFront]. Clipping a
 * full-height face was supposed to reveal its top row and in practice revealed the row below it,
 * so a stacked deck answered "what is this card worth" with the fiat conversion — a figure that
 * drifts with the rate — instead of the ZEC the card actually carries.
 */
@Composable
internal fun CardPeek(item: GiftCardListItem, stock: ZappGiftCardStock) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        BasicText(
            text = item.amount.getValue(),
            style = ZappTheme.typography.displaySecondary.copy(color = stock.figureInk ?: stock.ink),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).testTag(GiftCardListTag.AMOUNT),
        )
        StatusPill(
            status = item.status,
            hasBeenChecked = item.lastCheckedAt != null,
            isCheckRecent = item.isLastCheckRecent,
            stock = stock,
        )
    }
}

@Composable
internal fun CardFront(item: GiftCardListItem, stock: ZappGiftCardStock) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        // The peek line. On a stacked deck this strip is the whole card, so it carries the only
        // two things the stack is read for: what a card is worth, and whether it has been
        // collected. Nothing else belongs here — anything added pushes one of them out of sight.
        // A collapsed card draws [CardPeek] instead of a clipped copy of this face.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            BasicText(
                text = item.amount.getValue(),
                style = ZappTheme.typography.displaySecondary.copy(color = stock.figureInk ?: stock.ink),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).testTag(GiftCardListTag.AMOUNT),
            )
            StatusPill(
                status = item.status,
                hasBeenChecked = item.lastCheckedAt != null,
                isCheckRecent = item.isLastCheckRecent,
                stock = stock,
            )
        }

        // Below the peek, so opening a card is what reveals it. The fiat is absent whenever the
        // wallet has no rate to show — never a zero, which would read as a card worth nothing.
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item.fiat?.let {
                BasicText(
                    text = it.getValue(),
                    style = ZappTheme.typography.body.copy(color = stock.inkMuted),
                    maxLines = 1,
                )
            }
            item.message?.let {
                BasicText(
                    text = it,
                    style = ZappTheme.typography.body.copy(color = stock.inkMuted),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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
 * One hand-off, not two. The link is a URL, and the share sheet already offers copying it wherever
 * the platform supports that, so a second button beside it only asked the sender to choose between
 * two spellings of the same action.
 */
@Composable
private fun CardTools(item: GiftCardListItem, stock: ZappGiftCardStock) {
    val sharePickerText = stringResource(R.string.gift_card_list_share_picker)
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        item.handOff?.let { handOff ->
            CardTool(
                icon = Icons.Default.Share,
                label = stringResource(R.string.gift_card_list_share),
                stock = stock,
                onClick = { handOff.onShare(sharePickerText) },
            )
        }
        when (val funding = item.funding) {
            is GiftFundingControl.Ready -> {
                CardTool(
                    icon = Icons.Default.Refresh,
                    label = stringResource(R.string.gift_card_list_retry_funding),
                    stock = stock,
                    onClick = funding.onReview,
                )
            }

            GiftFundingControl.Running -> {
                CardTool(
                    icon = Icons.Default.Refresh,
                    label = stringResource(R.string.gift_card_list_retry_funding_running),
                    stock = stock,
                    isEnabled = false,
                    onClick = {},
                )
            }

            GiftFundingControl.Hidden -> {
                Unit
            }
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
                // The ScanTrack draws the same figure but cannot say it, and the percentage is the
                // one thing that distinguishes a long scan from a stalled one. Absent until the SDK
                // measures something, which is a while into a scan — hence the plain label then.
                val percent = check.progress?.fraction?.let { (it * PERCENT).toInt() }
                CardTool(
                    icon = Icons.Default.Close,
                    label =
                        percent
                            ?.let { stringResource(R.string.gift_card_list_check_stop_progress, it) }
                            ?: stringResource(R.string.gift_card_list_check_stop),
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
internal fun ScanTrack(fraction: Float?, stock: ZappGiftCardStock, modifier: Modifier = Modifier) {
    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxWidth()
                // The core, not the edge: an amber card's edge IS the mark's colour, and the bar
                // vanished into it exactly where a sender was waiting to watch it move.
                .height(TRACK_HEIGHT)
                .background(stock.core),
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
private fun StatusPill(
    status: GiftCardListStatus,
    hasBeenChecked: Boolean,
    isCheckRecent: Boolean,
    stock: ZappGiftCardStock,
) {
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
            text = stringResource(status.chipRes(hasBeenChecked, isCheckRecent)),
            style = ZappTheme.typography.chip.copy(color = if (isSettled) stock.inkMuted else stock.ink),
            maxLines = 1,
        )
    }
}

@Composable
internal fun CardBack(item: GiftCardListItem, stock: ZappGiftCardStock) {
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

private const val MESSAGE_LINES = 3
private const val DETAIL_LINES = 3
