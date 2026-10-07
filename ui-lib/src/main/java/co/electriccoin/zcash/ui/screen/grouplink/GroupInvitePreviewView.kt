// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.grouplink

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.zapp.ZappBottomActionBar
import co.electriccoin.zcash.ui.design.component.zapp.ZappButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappButtonVariant
import co.electriccoin.zcash.ui.design.component.zapp.ZappScreenHeader
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.stringRes

@Composable
internal fun GroupInvitePreviewView(
    state: GroupInvitePreviewState,
    modifier: Modifier = Modifier,
) {
    val c = ZappTheme.colors
    val t = ZappTheme.typography

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(c.bg)
                .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout)),
    ) {
        ZappScreenHeader(title = stringResource(R.string.group_invite_header))

        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(c.surfaceAlt),
                contentAlignment = Alignment.Center,
            ) {
                Icon(imageVector = Icons.Default.Groups, contentDescription = null, tint = c.text)
            }
            Spacer(Modifier.height(4.dp))

            val title = state.title
            if (title == null) {
                CircularProgressIndicator(color = c.accent, modifier = Modifier.size(28.dp))
            } else {
                Text(text = title.getValue(), style = t.sectionTitle, color = c.text, textAlign = TextAlign.Center)
            }
            state.body?.let {
                Text(text = it.getValue(), style = t.body, color = c.textMuted, textAlign = TextAlign.Center)
            }
            state.note?.let {
                Text(text = it.getValue(), style = t.caption, color = c.danger, textAlign = TextAlign.Center)
            }

            Spacer(Modifier.height(12.dp))
            state.primary?.let { ActionButton(it, ZappButtonVariant.Primary) }
            state.secondary?.let { ActionButton(it, ZappButtonVariant.Ghost) }
        }

        ZappBottomActionBar(onBack = state.onBack)
    }
}

@Composable
private fun ActionButton(
    button: ButtonState,
    variant: ZappButtonVariant,
) {
    ZappButton(
        text = button.text.getValue(),
        variant = variant,
        enabled = button.isEnabled && !button.isLoading,
        loading = button.isLoading,
        modifier = Modifier.fillMaxWidth(),
        onClick = button.onClick,
    )
}

@PreviewScreens
@Composable
private fun GroupInvitePreviewPreview() =
    ZcashTheme {
        GroupInvitePreviewView(
            state =
                GroupInvitePreviewState(
                    title = stringRes(R.string.group_invite_title_named, "Hiking Crew"),
                    body = stringRes(R.string.group_invite_body),
                    primary = ButtonState(stringRes(R.string.group_invite_join)),
                    secondary = ButtonState(stringRes(R.string.group_invite_not_now)),
                    onBack = {},
                ),
        )
    }
