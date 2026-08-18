// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.view

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.design.component.zapp.initialsOf
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.screen.chat.list.ChatListItemState

@Composable
internal fun ConversationItem(item: ChatListItemState) {
    val c = ZappTheme.colors
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = item.onClick)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            ConversationAvatar(name = item.displayName, group = item.isGroup)
            if (item.isPeerOnline) {
                // Square presence dot, inset with a bg-colored frame so it reads against the avatar.
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.BottomEnd)
                            .size(12.dp)
                            .background(c.bg, RectangleShape)
                            .padding(2.dp)
                            .background(c.success, RectangleShape),
                )
            }
        }

        Spacer(Modifier.size(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(
                    text = item.displayName,
                    style = ZappTheme.typography.rowTitle.copy(color = c.text),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                item.timeLabel?.let { ts ->
                    BasicText(
                        text = ts.getValue(),
                        style = ZappTheme.typography.caption.copy(color = c.textSubtle),
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(
                    text = item.lastMessage.getValue(),
                    style = ZappTheme.typography.rowSubtitle.copy(color = c.textMuted),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (item.unreadCount > 0) {
                    Box(
                        modifier =
                            Modifier
                                .padding(start = 8.dp)
                                .background(c.accent, RectangleShape)
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        BasicText(
                            text = "${item.unreadCount}",
                            style = ZappTheme.typography.chip.copy(color = c.onAccent),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationAvatar(name: String, group: Boolean) {
    val c = ZappTheme.colors
    val initials = remember(name) { initialsOf(name) }
    Box(
        modifier =
            Modifier
                .size(44.dp)
                .background(c.accent, RectangleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (group || initials.isBlank()) {
            Icon(
                imageVector = if (group) Icons.Default.Group else Icons.Default.Person,
                contentDescription = null,
                tint = c.onAccent,
                modifier = Modifier.size(20.dp),
            )
        } else {
            BasicText(
                text = initials,
                style = ZappTheme.typography.rowTitle.copy(color = c.onAccent),
            )
        }
    }
}
