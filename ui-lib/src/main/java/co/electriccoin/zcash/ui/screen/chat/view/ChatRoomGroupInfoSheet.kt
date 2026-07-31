// SPDX-License-Identifier: MIT OR Apache-2.0
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.ZashiModalBottomSheet
import co.electriccoin.zcash.ui.design.component.zapp.ZappInputField
import co.electriccoin.zcash.ui.design.component.zapp.initialsOf
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.screen.chat.room.ChatRoomAddMemberSheetState
import co.electriccoin.zcash.ui.screen.chat.room.ChatRoomGroupInfoSheetState
import co.electriccoin.zcash.ui.screen.chat.room.ChatRoomGroupRenameDialogState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GroupInfoSheet(state: ChatRoomGroupInfoSheetState) {
    val c = ZappTheme.colors
    ZashiModalBottomSheet(
        onDismissRequest = state.onDismiss,
        containerColor = c.surface,
        scrimColor = c.overlay,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 24.dp),
        ) {
            BasicText(
                text = state.groupName,
                style = ZappTheme.typography.sectionTitle.copy(color = c.text, fontWeight = FontWeight.Black),
            )
            Spacer(Modifier.height(4.dp))
            BasicText(
                text = stringResource(R.string.chat_group_info_members_fmt, state.members.size.toString()),
                style = ZappTheme.typography.caption.copy(color = c.textMuted),
            )
            Spacer(Modifier.height(16.dp))

            GroupActionRow(
                icon = Icons.Default.DriveFileRenameOutline,
                label = stringResource(R.string.chat_group_info_rename),
                onClick = state.onRename,
            )
            GroupActionRow(
                icon = Icons.Default.PersonAdd,
                label = stringResource(R.string.chat_group_info_add_member),
                onClick = state.onAddMember,
            )

            Spacer(Modifier.height(12.dp))
            BasicText(
                text = stringResource(R.string.chat_group_info_roster_label),
                style = ZappTheme.typography.eyebrow.copy(color = c.textSubtle),
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                items(items = state.members, key = { it.publicKey }) { member ->
                    MemberRow(name = member.displayName)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddMemberSheet(state: ChatRoomAddMemberSheetState) {
    val c = ZappTheme.colors
    ZashiModalBottomSheet(
        onDismissRequest = state.onDismiss,
        containerColor = c.surface,
        scrimColor = c.overlay,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 24.dp),
        ) {
            BasicText(
                text = stringResource(R.string.chat_group_info_add_member),
                style = ZappTheme.typography.sectionTitle.copy(color = c.text, fontWeight = FontWeight.Black),
            )
            Spacer(Modifier.height(12.dp))
            if (state.contacts.isEmpty()) {
                BasicText(
                    text = stringResource(R.string.chat_group_info_no_contacts),
                    style = ZappTheme.typography.body.copy(color = c.textMuted),
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(items = state.contacts, key = { it.publicKey }) { contact ->
                        GroupActionRow(
                            icon = Icons.Default.PersonAdd,
                            label = contact.displayName,
                            onClick = contact.onAdd,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun GroupRenameDialog(state: ChatRoomGroupRenameDialogState) {
    val c = ZappTheme.colors
    AlertDialog(
        onDismissRequest = state.onDismiss,
        containerColor = c.surface,
        titleContentColor = c.text,
        textContentColor = c.textMuted,
        shape = RectangleShape,
        title = {
            BasicText(
                text = stringResource(R.string.chat_group_info_rename),
                style = ZappTheme.typography.sectionTitle.copy(color = c.text, fontWeight = FontWeight.Black),
            )
        },
        text = {
            val field = remember { mutableStateOf(TextFieldValue(state.value, TextRange(state.value.length))) }
            ZappInputField(
                value = field.value,
                onValueChange = {
                    field.value = it
                    state.onValueChange(it.text)
                },
                placeholder = stringResource(R.string.chat_new_group_name_placeholder),
            )
        },
        confirmButton = {
            DialogTextButton(
                label = stringResource(R.string.chat_group_info_rename_save),
                color = if (state.canSave) c.accent else c.textSubtle,
                enabled = state.canSave,
                onClick = state.onSave,
            )
        },
        dismissButton = {
            DialogTextButton(
                label = stringResource(R.string.chat_group_info_rename_cancel),
                color = c.textMuted,
                onClick = state.onDismiss,
            )
        },
    )
}

@Composable
private fun GroupActionRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    val c = ZappTheme.colors
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .semantics(mergeDescendants = true) {
                    this.role = Role.Button
                    contentDescription = label
                }.padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = c.accent,
            modifier = Modifier.size(24.dp),
        )
        BasicText(
            text = label,
            style = ZappTheme.typography.rowTitle.copy(color = c.text),
        )
    }
}

@Composable
private fun MemberRow(name: String) {
    val c = ZappTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier.size(36.dp).background(c.accent, RectangleShape),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text = initialsOf(name),
                style = ZappTheme.typography.chip.copy(color = c.onAccent),
            )
        }
        BasicText(
            text = name,
            style = ZappTheme.typography.rowTitle.copy(color = c.text),
        )
    }
}
