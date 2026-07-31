// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.support

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.zapp.ZappBackButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappScreenHeader
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.chat.SupportChatArgs
import co.electriccoin.zcash.ui.screen.chat.room.ChatRoomInputState
import co.electriccoin.zcash.ui.screen.chat.view.ConfirmDialog
import co.electriccoin.zcash.ui.screen.chat.view.InputRow
import co.electriccoin.zcash.ui.screen.chat.view.MediaAttachmentSheet
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
internal fun SupportChatScreen(args: SupportChatArgs) {
    val viewModel = koinViewModel<SupportChatVM> { parametersOf(args) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.onScreenVisible()
        onPauseOrDispose { viewModel.onScreenHidden() }
    }
    SupportChatEffectsHandler(viewModel)
    BackHandler { state.onBack() }
    SupportChatView(state = state)
}

// ── View ──────────────────────────────────────────────────────────────────────

@Composable
private fun SupportChatView(state: SupportChatScreenState) {
    val c = ZappTheme.colors
    val uiState = state.uiState

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(c.bg)
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            SupportTopBar(
                title = stringRes(R.string.support_chat_title).getValue(),
                onBack = state.onBack,
                onLeave = state.onLeave,
                showOverflow = uiState is SupportChatUiState.Chat,
            )

            when (uiState) {
                SupportChatUiState.Loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = c.accent)
                    }
                }

                is SupportChatUiState.SelectCategory -> {
                    CategoryPickerFullScreen(
                        onSelected = state.onCategorySelected,
                        isSubmitting = uiState.isSubmitting,
                        modifier = Modifier.weight(1f),
                    )
                }

                is SupportChatUiState.Chat -> {
                    MessageList(
                        messages = uiState.messages,
                        modifier = Modifier.weight(1f),
                    )
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(c.border),
                    )
                    InputRow(
                        state =
                            ChatRoomInputState(
                                value = uiState.input,
                                placeholder = stringRes(R.string.support_chat_input_placeholder),
                                canSend = uiState.input.isNotBlank(),
                                attachContentDescription =
                                    stringRes(R.string.chat_room_attach_content_description),
                                sendContentDescription =
                                    stringRes(R.string.support_chat_send_content_description),
                                onChange = state.onInputChange,
                                onSendClick = state.onSend,
                                onAttachClick = state.onAttach,
                                onMediaCommitted = state.onMediaCommitted,
                            ),
                    )
                }
            }
        }

        state.leaveDialog?.let { dialog ->
            SupportLeaveDialog(state = dialog)
        }
    }

    state.mediaSheet?.let { sheet ->
        MediaAttachmentSheet(
            onChooseMedia = sheet.onChooseMedia,
            onAttachFile = sheet.onAttachFile,
            onTakePhoto = sheet.onTakePhoto,
            onDismiss = sheet.onDismiss,
        )
    }
}

// ── Top bar ───────────────────────────────────────────────────────────────────

@Composable
private fun SupportTopBar(
    title: String,
    onBack: () -> Unit,
    onLeave: () -> Unit,
    showOverflow: Boolean,
) {
    var showMenu by remember { mutableStateOf(false) }

    ZappScreenHeader(
        title = title,
        left = { ZappBackButton(onClick = onBack) },
        right = {
            if (showOverflow) {
                Box {
                    Box(
                        modifier =
                            Modifier
                                .size(36.dp)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = ripple(bounded = false),
                                    onClick = { showMenu = true },
                                ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.support_chat_overflow_content_description),
                            tint = ZappTheme.colors.text,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = {
                                BasicText(
                                    text = stringRes(R.string.support_chat_overflow_close).getValue(),
                                    style =
                                        ZappTheme.typography.body.copy(
                                            color = ZappTheme.colors.danger,
                                        ),
                                )
                            },
                            onClick = {
                                showMenu = false
                                onLeave()
                            },
                        )
                    }
                }
            }
        },
    )
}

// ── Category picker (bottom-anchored, thumb-reachable) ──────────────────────

@Composable
private fun CategoryPickerFullScreen(
    onSelected: (SupportCategory) -> Unit,
    isSubmitting: Boolean,
    modifier: Modifier = Modifier,
) {
    val c = ZappTheme.colors

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 48.dp)
                    .alpha(if (isSubmitting) PICKER_DIMMED_ALPHA else 1f),
        ) {
            BasicText(
                text = stringRes(R.string.support_chat_pick_topic).getValue(),
                style = ZappTheme.typography.sectionTitle.copy(color = c.text),
            )
            Spacer(Modifier.height(16.dp))
            SupportCategory.entries.forEach { category ->
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .background(c.surfaceAlt, RectangleShape)
                            .border(BorderStroke(1.dp, c.border), RectangleShape)
                            .clickable(
                                enabled = !isSubmitting,
                                interactionSource = remember { MutableInteractionSource() },
                                indication = ripple(color = c.accent),
                                onClick = { onSelected(category) },
                            ).padding(horizontal = 16.dp, vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(
                        text = stringResource(category.displayNameRes),
                        style = ZappTheme.typography.button.copy(color = c.text),
                    )
                }
            }
        }

        if (isSubmitting) {
            CircularProgressIndicator(
                color = c.accent,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

private const val PICKER_DIMMED_ALPHA = 0.4f

// ── Message list ──────────────────────────────────────────────────────────────

@Composable
private fun MessageList(
    messages: List<SupportUiMessage>,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size.coerceAtLeast(0))
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        state = listState,
        contentPadding = PaddingValues(top = 8.dp, bottom = navBarBottom + 8.dp),
    ) {
        items(items = messages, key = { it.id }) { msg ->
            SupportMessageBubble(message = msg)
        }
    }
}

// ── Message bubble ────────────────────────────────────────────────────────────

@Composable
private fun SupportMessageBubble(message: SupportUiMessage) {
    val c = ZappTheme.colors
    val isFromLocalUser = message.isFromLocalUser

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = if (isFromLocalUser) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            modifier =
                Modifier
                    .background(
                        if (isFromLocalUser) c.accent else c.surface,
                        RectangleShape,
                    ).padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            BasicText(
                text = message.content,
                style =
                    ZappTheme.typography.body.copy(
                        color = if (isFromLocalUser) c.onAccent else c.text,
                    ),
            )
        }
    }
}

// ── Leave dialog ──────────────────────────────────────────────────────────────

@Composable
private fun SupportLeaveDialog(state: SupportLeaveDialogState) {
    ConfirmDialog(
        title = stringRes(R.string.support_chat_leave_dialog_title),
        body = stringRes(R.string.support_chat_leave_dialog_message),
        confirmLabel = stringRes(R.string.support_chat_leave_dialog_confirm),
        cancelLabel = stringRes(R.string.support_chat_leave_dialog_cancel),
        onConfirm = state.onConfirm,
        onDismiss = state.onDismiss,
    )
}
