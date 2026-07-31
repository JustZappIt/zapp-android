// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.view

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.zapp.ZappRow
import co.electriccoin.zcash.ui.design.component.zapp.ZappRowDivider
import co.electriccoin.zcash.ui.design.theme.ZappTheme

@Composable
internal fun KeyExportRows(onSeedPhraseClick: () -> Unit, onP2pKeyClick: () -> Unit, showP2pKey: Boolean) {
    val c = ZappTheme.colors
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(c.surface, RectangleShape)
                .border(BorderStroke(1.dp, c.border), RectangleShape),
    ) {
        ZappRow(
            title = stringResource(R.string.chat_profile_seed_phrase_title),
            subtitle = stringResource(R.string.chat_profile_seed_phrase_subtitle),
            icon = Icons.Default.Key,
            iconBackground = c.accentSoft,
            iconTint = c.accentText,
            onClick = onSeedPhraseClick,
        )
        if (showP2pKey) {
            ZappRowDivider(inset = true)
            ZappRow(
                title = stringResource(R.string.chat_profile_p2p_key_title),
                subtitle = stringResource(R.string.chat_profile_p2p_key_subtitle),
                icon = Icons.Default.AccountBalanceWallet,
                iconBackground = c.accentSoft,
                iconTint = c.accentText,
                onClick = onP2pKeyClick,
            )
        }
    }
}

@Composable
internal fun BottomDock(onBack: () -> Unit, onDelete: () -> Unit) {
    val c = ZappTheme.colors
    val backLabel = stringResource(R.string.chat_profile_back_content_description)
    val deleteLabel = stringResource(R.string.chat_profile_delete_identity_content_description)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 18.dp)
                .padding(bottom = 8.dp)
                .background(c.surface)
                .border(BorderStroke(1.dp, c.border), RectangleShape),
    ) {
        Box(
            modifier =
                Modifier
                    .size(width = 72.dp, height = 52.dp)
                    .border(BorderStroke(1.dp, c.border), RectangleShape)
                    .clickable(onClick = onBack)
                    .semantics {
                        contentDescription = backLabel
                        role = Role.Button
                    },
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text = "←",
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
                    .background(c.danger, RectangleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(color = c.onAccent),
                        onClick = onDelete,
                    ).semantics {
                        contentDescription = deleteLabel
                        role = Role.Button
                    },
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text = stringResource(R.string.chat_profile_delete_button),
                style =
                    ZappTheme.typography.button.copy(
                        color = c.onAccent,
                        fontWeight = FontWeight.Black,
                        letterSpacing = LETTER_SPACING_DELETE,
                    ),
            )
        }
    }
}

private val LETTER_SPACING_DELETE = 0.6.sp
