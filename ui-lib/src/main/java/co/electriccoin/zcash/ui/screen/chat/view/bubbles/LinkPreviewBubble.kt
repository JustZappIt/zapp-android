// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.view.bubbles

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.screen.chat.linkpreview.LinkPreviewMetadata
import coil.compose.AsyncImage

@Composable
internal fun LinkPreviewBubble(
    metadata: LinkPreviewMetadata,
    isFromMe: Boolean,
    onClick: () -> Unit,
) {
    val c = ZappTheme.colors
    val foreground = if (isFromMe) c.onAccent else c.text
    val muted = if (isFromMe) c.onAccent.copy(alpha = PREVIEW_META_ALPHA) else c.textMuted
    val background = if (isFromMe) c.onAccent.copy(alpha = PREVIEW_BACKGROUND_ALPHA) else c.surfaceInput
    val openLinkDescription = stringResource(R.string.chat_room_open_link_content_description, metadata.siteName)

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(background, RectangleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(),
                    onClick = onClick,
                ).semantics {
                    contentDescription = openLinkDescription
                    role = Role.Button
                },
    ) {
        metadata.imageData?.let { imageData ->
            AsyncImage(
                model = imageData,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(PREVIEW_IMAGE_HEIGHT.dp)
                        .sizeIn(minWidth = PREVIEW_MIN_WIDTH.dp),
            )
        }
        Column(modifier = Modifier.padding(10.dp)) {
            BasicText(
                text = metadata.siteName,
                style = ZappTheme.typography.caption.copy(color = muted),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            metadata.title?.let { title ->
                Spacer(Modifier.height(2.dp))
                BasicText(
                    text = title,
                    style = ZappTheme.typography.body.copy(color = foreground),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            metadata.description?.let { description ->
                Spacer(Modifier.height(2.dp))
                BasicText(
                    text = description,
                    style = ZappTheme.typography.caption.copy(color = muted),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private const val PREVIEW_BACKGROUND_ALPHA = 0.14f
private const val PREVIEW_META_ALPHA = 0.74f
private const val PREVIEW_IMAGE_HEIGHT = 132
private const val PREVIEW_MIN_WIDTH = 220
