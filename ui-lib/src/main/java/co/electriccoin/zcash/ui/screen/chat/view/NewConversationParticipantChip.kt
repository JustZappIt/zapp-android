// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.view

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.screen.chat.newconv.NewConversationParticipantChip

@Composable
internal fun ParticipantChip(chip: NewConversationParticipantChip) {
    val c = ZappTheme.colors
    val description =
        stringResource(R.string.chat_new_conversation_remove_participant_content_description_fmt, chip.displayName)
    Row(
        modifier =
            Modifier
                .background(c.accentSoft, RectangleShape)
                .border(BorderStroke(1.dp, c.border), RectangleShape)
                .clickable(onClick = chip.onRemove)
                .semantics {
                    contentDescription = description
                    role = Role.Button
                }.padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BasicText(
            text = chip.displayName,
            style = ZappTheme.typography.chip.copy(color = c.accentText),
        )
        Icon(
            Icons.Default.Close,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = c.accentText,
        )
    }
}
