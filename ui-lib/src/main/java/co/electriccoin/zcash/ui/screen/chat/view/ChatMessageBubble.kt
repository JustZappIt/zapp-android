// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.view

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.wallet.ZecFiatRate
import co.electriccoin.zcash.ui.design.component.zapp.ZappBubbleShape
import co.electriccoin.zcash.ui.design.component.zapp.zappBubble
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.screen.chat.linkpreview.LinkPreviewMetadata
import co.electriccoin.zcash.ui.screen.chat.linkpreview.LinkPreviewRepository
import co.electriccoin.zcash.ui.screen.chat.linkpreview.detectWebUrls
import co.electriccoin.zcash.ui.screen.chat.linkpreview.firstWebUrl
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.view.bubbles.FileBubble
import co.electriccoin.zcash.ui.screen.chat.view.bubbles.LinkPreviewBubble
import co.electriccoin.zcash.ui.screen.chat.view.bubbles.LocationBubble
import co.electriccoin.zcash.ui.screen.chat.view.bubbles.MediaBubble
import co.electriccoin.zcash.ui.screen.chat.view.bubbles.PaymentRequestBubble
import co.electriccoin.zcash.ui.screen.chat.view.bubbles.TransactionBubble
import co.electriccoin.zcash.ui.screen.chat.view.bubbles.WalletAddressBubble
import kotlin.math.roundToInt

@Composable
internal fun MessageBubble(
    message: ChatMessage,
    modifier: Modifier = Modifier,
    onReplyToMessage: ((ChatMessage) -> Unit)? = null,
    onImageClick: ((ChatMessage) -> Unit)? = null,
    /** The message this one quotes, when the room has it; supplies the quote's thumbnail. */
    quotedMessage: ChatMessage? = null,
    /** Briefly true after a quote pointing at this message was tapped. */
    isHighlighted: Boolean = false,
    /** Tapping the quote block, with the quoted message's id. */
    onQuoteClick: ((String) -> Unit)? = null,
    localPublicKey: String? = null,
    fiatRate: ZecFiatRate? = null,
    paidRequestIds: Set<String> = emptySet(),
    onPayRequest: ((ChatMessage) -> Unit)? = null,
    onViewTransaction: ((String) -> Unit)? = null,
    onSendToAddress: ((String) -> Unit)? = null,
    onCopyMessage: ((ChatMessage) -> Unit)? = null,
    mediaTransferProgress: Float? = null,
    linkPreviewRepository: LinkPreviewRepository,
) {
    val c = ZappTheme.colors
    val isFromMe = message.isFromMe
    val kind = remember(message.contentType, message.content, message.mediaId) { bubbleKind(message) }
    val isText = kind == BubbleKind.TEXT
    val haptic = LocalHapticFeedback.current

    var offsetX by remember { mutableFloatStateOf(0f) }
    val highlight by
        animateColorAsState(
            targetValue = if (isHighlighted) c.accent.copy(alpha = HIGHLIGHT_ALPHA) else Color.Transparent,
            label = "quoteHighlight",
        )

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .background(highlight, RectangleShape),
        contentAlignment = if (isFromMe) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        SwipeReplyIndicator(offset = offsetX, isFromMe = isFromMe)

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .offset { IntOffset(offsetX.roundToInt(), 0) }
                    .swipeToReply(
                        isFromMe = isFromMe,
                        enabled = onReplyToMessage != null,
                        offset = { offsetX },
                        setOffset = { offsetX = it },
                    ) {
                        runCatching { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
                        onReplyToMessage?.invoke(message)
                    },
            horizontalAlignment = if (isFromMe) Alignment.End else Alignment.Start,
        ) {
            if (!isFromMe && message.senderName != null) {
                BasicText(
                    text = message.senderName,
                    style = ZappTheme.typography.chip.copy(color = c.accent),
                    modifier =
                        Modifier.padding(
                            start = 4.dp + if (isText) ZappBubbleShape.TAIL_DEPTH else 0.dp,
                            bottom = 2.dp,
                        ),
                )
            }

            val hasReply = message.replyToId != null
            Column(
                horizontalAlignment = if (isFromMe) Alignment.End else Alignment.Start,
                modifier = bubbleGroupModifier(isText = isText, hasReply = hasReply, isFromMe = isFromMe),
            ) {
                val quotedId = message.replyToId
                if (quotedId != null) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .background(c.surfaceInput, RectangleShape)
                                .then(
                                    if (onQuoteClick != null) {
                                        Modifier.clickable { onQuoteClick(quotedId) }
                                    } else {
                                        Modifier
                                    }
                                ).padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 6.dp),
                    ) {
                        QuotedReplyBlock(
                            senderName = message.replyToSenderName,
                            content = message.replyToContent,
                            contentType = message.replyToContentType,
                            quotedMessage = quotedMessage,
                        )
                    }
                }

                val contentModifier = if (hasReply) Modifier.fillMaxWidth() else Modifier
                MessageContent(
                    message = message,
                    isFromMe = isFromMe,
                    kind = kind,
                    hasReply = hasReply,
                    contentModifier = contentModifier,
                    onImageClick = onImageClick,
                    localPublicKey = localPublicKey,
                    fiatRate = fiatRate,
                    paidRequestIds = paidRequestIds,
                    onPayRequest = onPayRequest,
                    onViewTransaction = onViewTransaction,
                    onSendToAddress = onSendToAddress,
                    onCopyMessage = onCopyMessage,
                    mediaTransferProgress = mediaTransferProgress,
                    linkPreviewRepository = linkPreviewRepository,
                )
            }
        }
    }
}

/**
 * Sizes the quote-and-body group. When quoting, the group takes its widest row so the quoted block
 * and the message share one width. A text bubble draws the two as one tailed shape; the media,
 * file, payment, address and transaction bubbles keep their sharp boxes for now.
 */
@Composable
private fun bubbleGroupModifier(isText: Boolean, hasReply: Boolean, isFromMe: Boolean): Modifier {
    val c = ZappTheme.colors
    return Modifier
        .then(if (hasReply || isText) Modifier.widthIn(max = MAX_BUBBLE_WIDTH.dp) else Modifier)
        .then(if (hasReply) Modifier.width(IntrinsicSize.Max) else Modifier)
        .then(
            if (isText) {
                Modifier.zappBubble(
                    tail = if (isFromMe) ZappBubbleShape.Tail.TRAILING else ZappBubbleShape.Tail.LEADING,
                    fill = if (isFromMe) c.accent else c.surfaceAlt,
                )
            } else {
                Modifier
            },
        )
}

@Composable
private fun BoxScope.SwipeReplyIndicator(offset: Float, isFromMe: Boolean) {
    val visible =
        (offset > SWIPE_ICON_APPEAR_THRESHOLD && !isFromMe) ||
            (offset < -SWIPE_ICON_APPEAR_THRESHOLD && isFromMe)
    if (!visible) return
    Icon(
        Icons.AutoMirrored.Filled.Reply,
        contentDescription = stringResource(R.string.chat_room_reply_action),
        tint = ZappTheme.colors.textSubtle,
        modifier =
            Modifier
                .align(if (isFromMe) Alignment.CenterEnd else Alignment.CenterStart)
                .padding(horizontal = 8.dp)
                .size(20.dp),
    )
}

private fun Modifier.swipeToReply(
    isFromMe: Boolean,
    enabled: Boolean,
    offset: () -> Float,
    setOffset: (Float) -> Unit,
    onTriggered: () -> Unit,
): Modifier =
    pointerInput(enabled) {
        if (!enabled) return@pointerInput
        val triggerRange = if (isFromMe) -SWIPE_MAX_OFFSET..0f else 0f..SWIPE_MAX_OFFSET
        detectHorizontalDragGestures(
            onDragEnd = {
                val triggered =
                    (isFromMe && offset() < -SWIPE_THRESHOLD) || (!isFromMe && offset() > SWIPE_THRESHOLD)
                if (triggered) onTriggered()
                setOffset(0f)
            },
            onDragCancel = { setOffset(0f) },
        ) { _, dragAmount ->
            setOffset((offset() + dragAmount).coerceIn(triggerRange))
        }
    }

@Composable
private fun MessageContent(
    message: ChatMessage,
    isFromMe: Boolean,
    kind: BubbleKind,
    hasReply: Boolean,
    contentModifier: Modifier,
    onImageClick: ((ChatMessage) -> Unit)?,
    localPublicKey: String?,
    fiatRate: ZecFiatRate?,
    paidRequestIds: Set<String>,
    onPayRequest: ((ChatMessage) -> Unit)?,
    onViewTransaction: ((String) -> Unit)?,
    onSendToAddress: ((String) -> Unit)?,
    onCopyMessage: ((ChatMessage) -> Unit)?,
    mediaTransferProgress: Float?,
    linkPreviewRepository: LinkPreviewRepository,
) {
    when (kind) {
        BubbleKind.PAYMENT_REQUEST -> {
            val requestId = paymentRequestId(message)
            PaymentRequestBubble(
                message = message,
                isFromMe = isFromMe,
                localPublicKey = localPublicKey,
                fiatRate = fiatRate,
                isPaid = requestId != null && requestId in paidRequestIds,
                onPay = { onPayRequest?.invoke(it) },
            )
        }

        BubbleKind.WALLET_ADDRESS -> {
            WalletAddressBubble(
                message = message,
                isFromMe = isFromMe,
                onSendToAddress = onSendToAddress,
            )
        }

        BubbleKind.TRANSACTION -> {
            TransactionBubble(
                message = message,
                isFromMe = isFromMe,
                onViewTransaction = onViewTransaction,
            )
        }

        BubbleKind.LOCATION -> {
            LocationBubble(message = message, isFromMe = isFromMe)
        }

        BubbleKind.IMAGE -> {
            MediaBubble(
                message = message,
                isFromMe = isFromMe,
                onImageClick = onImageClick,
                transferProgress = mediaTransferProgress,
            )
        }

        BubbleKind.VIDEO -> {
            MediaBubble(
                message = message,
                isFromMe = isFromMe,
                transferProgress = mediaTransferProgress,
            )
        }

        BubbleKind.FILE -> {
            FileBubble(
                message = message,
                isFromMe = isFromMe,
                transferProgress = mediaTransferProgress,
            )
        }

        BubbleKind.TEXT -> {
            TextMessageBubble(
                message = message,
                isFromMe = isFromMe,
                fillWidth = hasReply,
                modifier = contentModifier,
                onCopy = onCopyMessage?.let { copy -> { copy(message) } },
                linkPreviewRepository = linkPreviewRepository,
            )
        }
    }
}

@Composable
private fun TextMessageBubble(
    message: ChatMessage,
    isFromMe: Boolean,
    fillWidth: Boolean = false,
    modifier: Modifier = Modifier,
    onCopy: (() -> Unit)? = null,
    linkPreviewRepository: LinkPreviewRepository,
) {
    val c = ZappTheme.colors
    val haptic = LocalHapticFeedback.current
    val uriHandler = LocalUriHandler.current
    val links = remember(message.content) { detectWebUrls(message.content) }
    val previewUrl = remember(message.content) { firstWebUrl(message.content) }
    val preview by
        produceState<LinkPreviewMetadata?>(initialValue = null, key1 = previewUrl, key2 = linkPreviewRepository) {
            value = previewUrl?.let { linkPreviewRepository.load(it) }
        }
    val linkColor = if (isFromMe) c.onAccent else c.accentText
    val annotatedMessage =
        remember(message.content, links, linkColor, uriHandler) {
            buildAnnotatedString {
                append(message.content)
                links.forEachIndexed { index, link ->
                    addLink(
                        LinkAnnotation.Clickable(
                            tag = "message_link_$index",
                            styles =
                                TextLinkStyles(
                                    style =
                                        SpanStyle(
                                            color = linkColor,
                                            textDecoration = TextDecoration.Underline,
                                        ),
                                ),
                        ) {
                            runCatching { uriHandler.openUri(link.url) }
                        },
                        start = link.start,
                        end = link.endExclusive,
                    )
                }
            }
        }
    val copyActionLabel = stringResource(R.string.chat_room_copy_action)
    val copyModifier =
        modifier.longPressToCopy(
            label = copyActionLabel,
            onCopy = onCopy,
            onHaptic = {
                runCatching { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
            },
        )
    Column(modifier = copyModifier.padding(12.dp)) {
        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = if (fillWidth) Modifier.fillMaxWidth() else Modifier,
        ) {
            BasicText(
                text = annotatedMessage,
                style =
                    ZappTheme.typography.body.copy(
                        color = if (isFromMe) c.onAccent else c.text,
                    ),
                modifier = Modifier.weight(1f, fill = fillWidth),
            )
            Spacer(modifier = Modifier.width(8.dp))
            BasicText(
                text = formatMessageTime(message.timestamp),
                style =
                    ZappTheme.typography.caption.copy(
                        fontSize = 10.sp,
                        color = if (isFromMe) c.onAccent.copy(alpha = OUTGOING_META_ALPHA) else c.textMuted,
                    ),
            )
            if (isFromMe) {
                Spacer(modifier = Modifier.width(4.dp))
                MessageStatusIndicator(
                    status = message.status,
                    mutedColor = c.onAccent.copy(alpha = OUTGOING_STATUS_ALPHA),
                    readColor = c.onAccent,
                )
            }
        }
        preview?.let { metadata ->
            Spacer(Modifier.height(10.dp))
            LinkPreviewBubble(
                metadata = metadata,
                isFromMe = isFromMe,
                onClick = { runCatching { uriHandler.openUri(metadata.url) } },
            )
        }
    }
}

private fun Modifier.longPressToCopy(
    label: String,
    onCopy: (() -> Unit)?,
    onHaptic: () -> Unit,
): Modifier {
    if (onCopy == null) return this
    return pointerInput(label, onCopy) {
        detectTapGestures(
            onLongPress = {
                onHaptic()
                onCopy()
            },
        )
    }.semantics {
        onLongClick(label = label) {
            onHaptic()
            onCopy()
            true
        }
    }
}

/**
 * The quote above a reply's body. A quoted photo, file, payment request, transaction, address
 * or location is named by its kind, with a thumbnail when the room still has the picture; a
 * quote from a client that predates `replyToContentType` reads as text, as before.
 */
@Composable
private fun QuotedReplyBlock(
    senderName: String?,
    content: String?,
    contentType: String?,
    quotedMessage: ChatMessage?,
) {
    val c = ZappTheme.colors
    val kind = replyQuoteKind(contentType)
    val icon = replyQuoteIcon(kind)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .width(3.dp)
                    .height(36.dp)
                    .background(c.accent),
        )
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
        ) {
            BasicText(
                text = senderName ?: stringResource(R.string.chat_room_reply_unknown_sender),
                style = ZappTheme.typography.chip.copy(color = c.accent),
                maxLines = 1,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = c.textMuted,
                        modifier = Modifier.size(QUOTE_ICON_SIZE.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
                BasicText(
                    text = replyQuoteLine(kind, content),
                    style = ZappTheme.typography.caption.copy(color = c.textMuted),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (kind.showsThumbnail && quotedMessage != null) {
            ReplyQuoteThumbnail(
                message = quotedMessage,
                size = QUOTE_THUMBNAIL_SIZE.dp,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

private const val HIGHLIGHT_ALPHA = 0.18f
private const val QUOTE_ICON_SIZE = 14
private const val QUOTE_THUMBNAIL_SIZE = 36
private const val OUTGOING_META_ALPHA = 0.7f
private const val OUTGOING_STATUS_ALPHA = 0.55f
private const val MAX_BUBBLE_WIDTH = 280
private const val SWIPE_MAX_OFFSET = 120f
private const val SWIPE_THRESHOLD = 80f
private const val SWIPE_ICON_APPEAR_THRESHOLD = 20f
