// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.receive

import android.content.Intent
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.QrState
import co.electriccoin.zcash.ui.design.component.ZashiQr
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.util.StringResource

// The receive screens' parts, shared so that receiving dollars looks like receiving ZEC.

/** An address's code in a square frame, [centerImage] over its middle. */
@Composable
internal fun ReceiveQrFrame(
    qrData: String,
    modifier: Modifier = Modifier,
    @DrawableRes centerImage: Int? = null,
    contentDescription: StringResource? = null,
) {
    val c = ZappTheme.colors
    Box(
        modifier =
            modifier
                .background(c.bg, RectangleShape)
                .border(BorderStroke(1.dp, c.border), RectangleShape)
                .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        ZashiQr(
            state = QrState(qrData = qrData, contentDescription = contentDescription, centerImage = centerImage),
            modifier = Modifier.fillMaxWidth(QR_WIDTH),
        )
    }
}

/** A note under a small label, set off by a bar down its side: a tip, or a warning in the danger color. */
@Composable
internal fun ReceiveTip(
    label: String,
    text: String,
    modifier: Modifier = Modifier,
    isWarning: Boolean = false,
) {
    val c = ZappTheme.colors
    Row(
        modifier = modifier.fillMaxWidth().height(IntrinsicSize.Min),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier =
                Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(if (isWarning) c.danger else c.accent, RectangleShape),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = label,
                style =
                    ZappTheme.typography.eyebrow.copy(
                        color = if (isWarning) c.danger else c.textSubtle,
                        fontSize = 10.sp,
                        letterSpacing = 1.8.sp,
                        fontWeight = FontWeight.Black,
                    ),
            )
            Spacer(Modifier.height(4.dp))
            BasicText(
                text = text,
                style =
                    ZappTheme.typography.body.copy(
                        color = if (isWarning) c.text else c.textMuted,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                    ),
            )
        }
    }
}

/** One tab per kind of address, the picked one filled. */
@Composable
internal fun ReceiveTabSwitcher(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = ZappTheme.colors
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 8.dp)
                .background(c.surface, RectangleShape)
                .border(BorderStroke(1.dp, c.border), RectangleShape)
                .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        labels.forEachIndexed { index, label ->
            val isSelected = index == selectedIndex
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 34.dp)
                        .background(if (isSelected) c.accent else Color.Transparent, RectangleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(color = c.accent),
                            onClick = { onSelect(index) },
                        ),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text = label,
                    style =
                        ZappTheme.typography.caption.copy(
                            color = if (isSelected) c.onAccent else c.textMuted,
                            fontWeight = if (isSelected) FontWeight.Black else FontWeight.Normal,
                        ),
                )
            }
        }
    }
}

/** Hands [address] to the system's share sheet; does nothing while there's none to share. */
@Composable
internal fun ReceiveShareButton(
    address: String?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val c = ZappTheme.colors
    Box(
        modifier =
            modifier
                .height(48.dp)
                .border(BorderStroke(1.dp, c.border), RectangleShape)
                .clickable(enabled = address != null) {
                    val intent =
                        Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, address)
                        }
                    context.startActivity(Intent.createChooser(intent, null))
                }.semantics { role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = stringResource(R.string.receive_share),
            style =
                ZappTheme.typography.button.copy(
                    color = if (address != null) c.textMuted else c.textSubtle,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.4.sp,
                ),
        )
    }
}

/** Back, and beside it the screen's [action], filled. */
@Composable
internal fun ReceiveBottomDock(
    onBack: () -> Unit,
    action: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    isActionEnabled: Boolean = true,
) {
    val c = ZappTheme.colors
    val backContentDescription = stringResource(R.string.receive_back_content_description)
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 18.dp)
                .padding(bottom = 8.dp)
                .background(c.surface)
                .border(BorderStroke(1.dp, c.border), RectangleShape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(width = 72.dp, height = 52.dp)
                    .border(BorderStroke(1.dp, c.border), RectangleShape)
                    .clickable(onClick = onBack)
                    .semantics {
                        contentDescription = backContentDescription
                        role = Role.Button
                    },
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text = stringResource(R.string.receive_back_glyph),
                style =
                    ZappTheme.typography.button.copy(
                        color = c.text,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Black,
                    ),
            )
        }
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .height(52.dp)
                    .background(if (isActionEnabled) c.accent else c.surfaceAlt, RectangleShape)
                    .clickable(enabled = isActionEnabled, onClick = onAction)
                    .semantics { role = Role.Button },
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text = action,
                style =
                    ZappTheme.typography.button.copy(
                        color = if (isActionEnabled) c.onAccent else c.textSubtle,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 0.6.sp,
                    ),
            )
        }
    }
}

// The share of the frame's width the code fills.
private const val QR_WIDTH = 0.92f
