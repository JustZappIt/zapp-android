// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.support

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.zapp.ZappBackButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappFab
import co.electriccoin.zcash.ui.design.component.zapp.ZappRowDivider
import co.electriccoin.zcash.ui.design.component.zapp.ZappScreenHeader
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.theme.colors.ZappNavBar
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.chat.view.ConfirmDialog
import co.electriccoin.zcash.ui.screen.chat.view.SwipeToRevealActionRow
import org.koin.androidx.compose.koinViewModel

@Composable
internal fun SupportTicketListScreen() {
    val viewModel = koinViewModel<SupportTicketListVM>()
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    BackHandler { state.onBack() }
    SupportTicketListView(state = state)
}

@Composable
private fun SupportTicketListView(state: SupportTicketListState) {
    val c = ZappTheme.colors

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(c.bg)
                .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            ZappScreenHeader(title = stringRes(R.string.support_chat_title).getValue())

            when {
                state.isLoading -> {
                    Box(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = c.accent)
                    }
                }

                state.tickets.isEmpty() -> {
                    TicketEmptyState(modifier = Modifier.weight(1f))
                }

                else -> {
                    val navBarBottom =
                        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding =
                            PaddingValues(
                                top = 4.dp,
                                bottom = navBarBottom + ZappNavBar.CLEARANCE_DP.dp,
                            ),
                    ) {
                        items(items = state.tickets, key = { it.conversationId }) { ticket ->
                            SwipeToRevealActionRow(
                                key = ticket.conversationId,
                                actionLabel = stringRes(R.string.chat_list_close_action),
                                onAction = ticket.onCloseSwipe,
                            ) {
                                TicketRow(ticket = ticket)
                            }
                            ZappRowDivider(inset = true)
                        }
                    }
                }
            }
        }

        ZappFab(
            icon = Icons.Default.Add,
            contentDescription = stringRes(R.string.support_ticket_list_new_content_description).getValue(),
            onClick = state.onNewTicket,
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(
                        end = 20.dp,
                        bottom = ZappNavBar.PUSHED_FLOATING_MARGIN_DP.dp,
                    ),
        )

        ZappBackButton(
            onClick = state.onBack,
            modifier =
                Modifier
                    .align(Alignment.BottomStart)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(
                        start = 20.dp,
                        bottom = ZappNavBar.PUSHED_FLOATING_MARGIN_DP.dp,
                    ),
        )

        state.closeDialog?.let { dialog ->
            SupportCloseDialog(state = dialog)
        }
    }
}

@Composable
private fun TicketRow(ticket: SupportTicketItem) {
    val c = ZappTheme.colors
    val categoryLabelText = ticket.categoryLabel.getValue()

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = ticket.onClick)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(44.dp)
                    .background(c.accent, RectangleShape),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text = categoryLabelText.firstOrNull()?.uppercase().orEmpty(),
                style = ZappTheme.typography.sectionTitle.copy(color = c.onAccent),
            )
        }

        Spacer(Modifier.size(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                BasicText(
                    text = categoryLabelText,
                    style = ZappTheme.typography.rowTitle.copy(color = c.text),
                    maxLines = 1,
                )
                ticket.timeLabel?.let { time ->
                    BasicText(
                        text = time.getValue(),
                        style = ZappTheme.typography.caption.copy(color = c.textMuted),
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            BasicText(
                text =
                    ticket.lastMessage?.getValue()
                        ?: stringRes(R.string.chat_list_no_messages).getValue(),
                style = ZappTheme.typography.rowSubtitle.copy(color = c.textMuted),
                maxLines = 1,
            )
        }

        if (ticket.unreadCount > 0) {
            Box(
                modifier =
                    Modifier
                        .padding(start = 8.dp)
                        .background(c.accent, RectangleShape)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                BasicText(
                    text = "${ticket.unreadCount}",
                    style = ZappTheme.typography.chip.copy(color = c.onAccent),
                )
            }
        }
    }
}

@Composable
private fun TicketEmptyState(modifier: Modifier = Modifier) {
    val c = ZappTheme.colors
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Chat,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = c.textSubtle,
            )
            Spacer(Modifier.height(12.dp))
            BasicText(
                text = stringRes(R.string.support_ticket_list_empty_title).getValue(),
                style = ZappTheme.typography.sectionTitle.copy(color = c.text),
            )
            Spacer(Modifier.height(6.dp))
            BasicText(
                text = stringRes(R.string.support_ticket_list_empty_subtitle).getValue(),
                style = ZappTheme.typography.body.copy(color = c.textMuted),
            )
        }
    }
}

@Composable
private fun SupportCloseDialog(state: SupportLeaveDialogState) {
    ConfirmDialog(
        title = stringRes(R.string.support_ticket_close_dialog_title),
        body = stringRes(R.string.support_ticket_close_dialog_message),
        confirmLabel = stringRes(R.string.support_ticket_close_dialog_confirm),
        cancelLabel = stringRes(R.string.support_ticket_close_dialog_cancel),
        onConfirm = state.onConfirm,
        onDismiss = state.onDismiss,
    )
}
