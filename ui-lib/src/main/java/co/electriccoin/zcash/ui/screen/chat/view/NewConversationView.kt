// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.view

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.zapp.ZappInputField
import co.electriccoin.zcash.ui.design.component.zapp.ZappScreenHeader
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.chat.newconv.NewConversationGroupNameDialogState
import co.electriccoin.zcash.ui.screen.chat.newconv.NewConversationRejoinDialogState
import co.electriccoin.zcash.ui.screen.chat.newconv.NewConversationState

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NewConversationView(state: NewConversationState, modifier: Modifier = Modifier) {
    val c = ZappTheme.colors

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(c.bg)
                .windowInsetsPadding(WindowInsets.statusBars)
                .imePadding(),
    ) {
        ZappScreenHeader(title = state.title.getValue())

        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
        ) {
            if (state.showEmptyState) {
                EmptyState(
                    modifier =
                        Modifier
                            .align(Alignment.Center)
                            .padding(horizontal = 28.dp),
                )
            } else {
                ConversationBody(state = state)
            }
        }

        SearchField(
            value = state.searchInput,
            onChange = state.onSearchInputChange,
            onClear = state.onClearSearch,
        )

        BottomDock(state = state)
    }

    state.groupNameDialog?.let { GroupNameDialog(state = it) }
    state.rejoinDialog?.let { RejoinConfirmationDialog(state = it) }
}

@Composable
private fun RejoinConfirmationDialog(state: NewConversationRejoinDialogState) {
    ConfirmDialog(
        title = stringRes(R.string.chat_new_rejoin_dialog_title),
        body = stringRes(R.string.chat_new_rejoin_dialog_message, state.displayName),
        confirmLabel = stringRes(R.string.chat_new_rejoin_dialog_confirm),
        cancelLabel = stringRes(R.string.chat_new_rejoin_dialog_cancel),
        onConfirm = state.onConfirm,
        onDismiss = state.onDismiss,
        isDestructive = false,
    )
}

@Composable
private fun GroupNameDialog(state: NewConversationGroupNameDialogState) {
    val c = ZappTheme.colors
    AlertDialog(
        onDismissRequest = state.onDismiss,
        containerColor = c.surface,
        titleContentColor = c.text,
        textContentColor = c.textMuted,
        shape = RectangleShape,
        title = {
            BasicText(
                text = stringResource(R.string.chat_new_group_name_title),
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
                label = stringResource(R.string.chat_new_group_name_confirm),
                color = if (state.canConfirm) c.accent else c.textSubtle,
                enabled = state.canConfirm,
                onClick = state.onConfirm,
            )
        },
        dismissButton = {
            DialogTextButton(
                label = stringResource(R.string.chat_new_group_name_cancel),
                color = c.textMuted,
                onClick = state.onDismiss,
            )
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConversationBody(state: NewConversationState) {
    val c = ZappTheme.colors
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp),
    ) {
        if (state.selectedParticipants.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                state.selectedParticipants.forEach { chip -> ParticipantChip(chip = chip) }
            }
            Spacer(Modifier.height(12.dp))
        }

        if (state.isPublicKeyDetected) {
            Spacer(Modifier.height(12.dp))
            PublicKeyDetectedBanner(
                detectedKey = state.detectedPublicKey,
                onAdd = state.onAddDetectedKey,
            )
            Spacer(Modifier.height(12.dp))
        }

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(items = state.contacts, key = { it.contact.publicKey }) { item ->
                ContactSelectRow(item = item)
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 56.dp)
                            .height(1.dp)
                            .background(c.border, RectangleShape),
                )
            }
        }
    }
}
