// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.view

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.design.component.zapp.ZappBackButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappScreenHeader
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.screen.chat.linkpreview.LinkPreviewRepository
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.room.ChatRoomState
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.util.Calendar

@Composable
internal fun ChatRoomView(
    state: ChatRoomState,
    onReplyToMessage: (ChatMessage) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val c = ZappTheme.colors
    val linkPreviewRepository: LinkPreviewRepository = koinInject()
    val listState = rememberLazyListState()
    val listItems =
        remember(state.messages, state.firstUnreadMessageId) {
            buildChatListItems(state.messages, state.firstUnreadMessageId)
        }
    val paidIds = remember(state.messages) { paidRequestIds(state.messages) }
    var viewerMessage by remember { mutableStateOf<ChatMessage?>(null) }
    var positionedInitialMessages by remember { mutableStateOf(false) }
    val latestMessage = state.messages.lastOrNull()
    val shouldFollowLatest = !listState.canScrollForward

    LaunchedEffect(state.isLoading, latestMessage?.id, listItems.size) {
        latestMessage ?: return@LaunchedEffect
        if (state.isLoading) return@LaunchedEffect
        if (!positionedInitialMessages) {
            val unreadIndex = listItems.indexOfFirst { it is ChatListItem.UnreadSeparator }
            listState.scrollToItem(if (unreadIndex >= 0) unreadIndex else listItems.lastIndex)
            positionedInitialMessages = true
        } else if (latestMessage.isFromMe || shouldFollowLatest) {
            listState.animateScrollToItem(listItems.lastIndex)
        }
    }

    Scaffold(
        topBar = {
            ZappScreenHeader(
                title = state.title.getValue(),
                subtitle = state.subtitle.getValue(),
                onTitleClick = if (state.isTitleClickable) state.onTitleClick else null,
                left = { ZappBackButton(onClick = state.onBack) },
                right = { NetworkChip(state = state.networkChip) },
            )
        },
        containerColor = c.bg,
        modifier =
            modifier
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars),
    ) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
        ) {
            LazyColumn(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 8.dp),
            ) {
                if (state.isLoading && state.messages.isEmpty()) {
                    item {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(color = c.accent)
                        }
                    }
                }
                itemsIndexed(
                    items = listItems,
                    key = { index, item ->
                        when (item) {
                            // Index-suffixed: a duplicate dayKey must not crash the list with
                            // "Key already used". Messages keep stable id-based keys.
                            is ChatListItem.DateSeparator -> "sep_${item.dayKey}_$index"

                            ChatListItem.UnreadSeparator -> "unread"

                            is ChatListItem.Message -> "msg_${item.message.id}"
                        }
                    },
                ) { _, item ->
                    when (item) {
                        is ChatListItem.DateSeparator -> {
                            ChatDateSeparator(
                                epochMillis = item.epochMillis,
                                modifier = Modifier.animateItem(),
                            )
                        }

                        ChatListItem.UnreadSeparator -> {
                            ChatUnreadSeparator(modifier = Modifier.animateItem())
                        }

                        is ChatListItem.Message -> {
                            MessageBubble(
                                message = item.message,
                                onReplyToMessage = onReplyToMessage,
                                onImageClick = { viewerMessage = it },
                                modifier = Modifier.animateItem(),
                                localPublicKey = state.localPublicKey,
                                fiatRate = state.fiatRate,
                                paidRequestIds = paidIds,
                                onPayRequest = state.onPayRequest,
                                onViewTransaction = state.onViewTransaction,
                                onSendToAddress = state.onSendToAddress,
                                onCopyMessage = state.onCopyMessage,
                                mediaTransferProgress =
                                    item.message.mediaId?.let { state.mediaTransferProgress[it] },
                                linkPreviewRepository = linkPreviewRepository,
                            )
                        }
                    }
                }
            }

            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(c.border),
            )

            InputRow(state = state.input)
        }
    }

    state.attachmentSheet?.let {
        AttachmentSheet(
            isGroup = it.isGroup,
            onShareAddress = it.onShareAddress,
            onSendZec = it.onSendZec,
            onSplitBill = it.onSplitBill,
            onAttachMedia = it.onAttachMedia,
            onDismiss = it.onDismiss,
        )
    }

    state.splitSheet?.let { SplitBillSheet(state = it) }

    state.networkSheet?.let { sheet ->
        NetworkDetailsSheet(
            connectionStatus = sheet.connectionStatus,
            peerCount = sheet.peerCount,
            dhtHealth = sheet.dhtHealth,
            connectionDetails = sheet.connectionDetails,
            onDismiss = sheet.onDismiss,
        )
    }

    state.editContactSheet?.let { EditChatContactSheet(state = it) }

    state.groupInfoSheet?.let { GroupInfoSheet(state = it) }

    state.groupRenameDialog?.let { GroupRenameDialog(state = it) }

    state.addMemberSheet?.let { AddMemberSheet(state = it) }

    state.mediaSheet?.let {
        MediaAttachmentSheet(
            onChooseMedia = it.onChooseMedia,
            onAttachFile = it.onAttachFile,
            onTakePhoto = it.onTakePhoto,
            onShareLocation = it.onShareLocation,
            onDismiss = it.onDismiss,
        )
    }

    state.blockDialog?.let { BlockUserDialog(state = it) }

    viewerMessage?.let { msg ->
        ImageViewerOverlay(
            message = msg,
            onDismiss = { viewerMessage = null },
        )
    }
}

private sealed interface ChatListItem {
    data class DateSeparator(
        val dayKey: Long,
        val epochMillis: Long
    ) : ChatListItem

    data object UnreadSeparator : ChatListItem

    data class Message(
        val message: ChatMessage
    ) : ChatListItem
}

private fun buildChatListItems(
    messages: List<ChatMessage>,
    firstUnreadMessageId: String?,
): List<ChatListItem> {
    if (messages.isEmpty()) return emptyList()
    val items = mutableListOf<ChatListItem>()
    var lastDayKey = -1L
    for (message in messages) {
        val dayKey = message.timestamp.toDayKey()
        if (dayKey != lastDayKey) {
            items.add(ChatListItem.DateSeparator(dayKey = dayKey, epochMillis = message.timestamp))
            lastDayKey = dayKey
        }
        if (message.id == firstUnreadMessageId) {
            items.add(ChatListItem.UnreadSeparator)
        }
        items.add(ChatListItem.Message(message))
    }
    return items
}

private fun Long.toDayKey(): Long {
    val cal = Calendar.getInstance()
    cal.timeInMillis = this
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}
