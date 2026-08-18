// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.view

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.zapp.initialsOf
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.screen.chat.model.ChatContact
import co.electriccoin.zcash.ui.screen.chat.newconv.NewConversationContactItem

@Composable
internal fun ContactSelectRow(item: NewConversationContactItem) {
    val c = ZappTheme.colors
    val contact: ChatContact = item.contact
    val initials = remember(contact.name) { initialsOf(contact.name) }
    val selectedLabel = stringResource(R.string.chat_new_conversation_selected_content_description)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = item.onToggle)
                .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(40.dp)
                    .background(c.accent, RectangleShape),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(initials, style = ZappTheme.typography.rowTitle.copy(color = c.onAccent))
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                contact.name,
                style = ZappTheme.typography.rowTitle.copy(color = c.text),
            )
            BasicText(
                "${contact.publicKey.take(CONTACT_KEY_HEAD)}...${contact.publicKey.takeLast(CONTACT_KEY_TAIL)}",
                style = ZappTheme.typography.mono.copy(color = c.textMuted),
            )
        }

        if (item.isSelected) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = selectedLabel,
                tint = c.accent,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

private const val CONTACT_KEY_HEAD = 8
private const val CONTACT_KEY_TAIL = 4
