// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.view

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.zapp.ZappBackButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappChipVariant
import co.electriccoin.zcash.ui.design.component.zapp.ZappFab
import co.electriccoin.zcash.ui.design.component.zapp.ZappScreenHeader
import co.electriccoin.zcash.ui.design.component.zapp.ZappStatusChip
import co.electriccoin.zcash.ui.design.component.zapp.ellipsizeAddress
import co.electriccoin.zcash.ui.design.component.zapp.initialsOf
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.theme.colors.ZappNavBar
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.screen.chat.contacts.ChatContactsState
import co.electriccoin.zcash.ui.screen.chat.model.ChatContact

@Composable
internal fun ChatContactsView(
    state: ChatContactsState,
    showBackButton: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val contacts = state.contacts
    val c = ZappTheme.colors
    val grouped =
        remember(contacts) {
            contacts
                .sortedBy { it.name.lowercase() }
                .groupBy { (it.name.firstOrNull() ?: '?').uppercaseChar() }
        }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(c.bg)
                .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            ZappScreenHeader(
                title = state.title.getValue(),
                right = {
                    ZappStatusChip(
                        text = stringResource(R.string.chat_contacts_saved_count_fmt, contacts.size),
                        variant = ZappChipVariant.Muted,
                    )
                },
            )

            if (contacts.isEmpty()) {
                EmptyContactsPlaceholder()
            } else {
                val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding =
                        PaddingValues(
                            top = 4.dp,
                            bottom = navBarBottom + ZappNavBar.CLEARANCE_DP.dp,
                        ),
                ) {
                    grouped.forEach { (letter, bucket) ->
                        item(key = "header-$letter") {
                            BasicText(
                                text = letter.toString(),
                                style = ZappTheme.typography.groupLabel.copy(color = c.textMuted),
                                modifier =
                                    Modifier.padding(
                                        start = 18.dp,
                                        end = 18.dp,
                                        top = 14.dp,
                                        bottom = 4.dp,
                                    ),
                            )
                        }
                        items(
                            items = bucket,
                            key = { it.publicKey },
                        ) { contact ->
                            ContactListItem(
                                contact = contact,
                                onChat = { state.onStartChat(contact.publicKey) },
                                onEdit = { state.onEditSheetOpen(contact) },
                            )
                        }
                    }
                }
            }
        }

        val floatingBottom =
            if (showBackButton) {
                ZappNavBar.PUSHED_FLOATING_MARGIN_DP.dp
            } else {
                ZappNavBar.FAB_BOTTOM_PADDING_DP.dp
            }
        ZappFab(
            icon = Icons.Default.PersonAdd,
            contentDescription = stringResource(R.string.chat_contacts_add_content_description),
            onClick = state.onAddSheetOpen,
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(
                        end = 20.dp,
                        bottom = floatingBottom,
                    ),
        )

        if (showBackButton) {
            ZappBackButton(
                onClick = state.onBack,
                modifier =
                    Modifier
                        .align(Alignment.BottomStart)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(
                            start = 20.dp,
                            bottom = floatingBottom,
                        ),
            )
        }
    }

    state.addSheet?.let { AddChatContactSheet(state = it) }
    state.editSheet?.let { EditChatContactSheet(state = it) }
    state.blockDialog?.let { BlockUserDialog(state = it) }
}

@Composable
private fun EmptyContactsPlaceholder() {
    val c = ZappTheme.colors
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp),
        ) {
            Icon(
                Icons.Default.Contacts,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = c.textSubtle,
            )
            Spacer(Modifier.height(12.dp))
            BasicText(
                stringResource(R.string.chat_contacts_empty_title),
                style = ZappTheme.typography.sectionTitle.copy(color = c.text),
            )
            Spacer(Modifier.height(6.dp))
            BasicText(
                stringResource(R.string.chat_contacts_empty_subtitle),
                style = ZappTheme.typography.body.copy(color = c.textMuted),
            )
        }
    }
}

@Composable
private fun ContactListItem(
    contact: ChatContact,
    onChat: () -> Unit,
    onEdit: () -> Unit,
) {
    val c = ZappTheme.colors
    val initials = remember(contact.name) { initialsOf(contact.name) }
    val shortKey = remember(contact.publicKey) { contact.publicKey.ellipsizeAddress() }
    val startChatLabel = stringResource(R.string.chat_contacts_start_chat_content_description)

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onEdit)
                .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(40.dp)
                    .background(c.accent, RectangleShape),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text = initials,
                style = ZappTheme.typography.rowTitle.copy(color = c.onAccent),
            )
        }

        Spacer(Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = contact.name,
                style = ZappTheme.typography.rowTitle.copy(color = if (contact.isBlocked) c.textMuted else c.text),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (contact.isBlocked) {
                BasicText(
                    text = stringResource(R.string.chat_contacts_blocked_badge),
                    style = ZappTheme.typography.caption.copy(color = c.danger),
                )
            } else {
                BasicText(
                    text = shortKey,
                    style = ZappTheme.typography.mono.copy(color = c.textMuted),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // No Start-Chat affordance for a blocked contact; unblock is via the edit sheet.
        if (!contact.isBlocked) {
            Box(
                modifier =
                    Modifier
                        .size(48.dp)
                        .clickable(onClick = onChat)
                        .semantics {
                            contentDescription = startChatLabel
                            role = Role.Button
                        },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Chat,
                    contentDescription = null,
                    tint = c.accent,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}
