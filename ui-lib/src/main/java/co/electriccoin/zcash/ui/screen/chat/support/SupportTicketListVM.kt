// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.support

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.chat.SupportChatArgs
import co.electriccoin.zcash.ui.screen.chat.common.formatRelativeTime
import co.electriccoin.zcash.ui.screen.chat.common.runChatCall
import co.electriccoin.zcash.ui.screen.chat.model.ChatConversation
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.justzappit.zappmessaging.ZappMessagingSDK

class SupportTicketListVM(
    private val application: Application,
    private val sdk: ZappMessagingSDK,
    private val navigationRouter: NavigationRouter,
) : ViewModel() {
    private val tickets = MutableStateFlow<List<TicketSnapshot>?>(null)
    private val closeTarget = MutableStateFlow<TicketSnapshot?>(null)
    private val categoryCache = mutableMapOf<String, SupportCategory?>()

    init {
        refresh()
        observeConversations()
        observeMessageEvents()
    }

    val state: StateFlow<SupportTicketListState> =
        combine(tickets, closeTarget) { list, target ->
            SupportTicketListState(
                tickets = list.orEmpty().map { it.toItem() },
                isLoading = list == null,
                onNewTicket = ::onNewTicket,
                onBack = ::onBack,
                closeDialog =
                    target?.let { t ->
                        SupportLeaveDialogState(
                            onConfirm = { onCloseConfirm(t) },
                            onDismiss = ::onCloseDismiss,
                        )
                    },
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue =
                SupportTicketListState(
                    tickets = emptyList(),
                    isLoading = true,
                    onNewTicket = ::onNewTicket,
                    onBack = ::onBack,
                    closeDialog = null,
                ),
        )

    fun refresh() {
        viewModelScope.launch {
            runChatCall("SupportTicketListVM: refresh failed") {
                sdk.refreshConversations()
            }
        }
    }

    private fun observeConversations() {
        viewModelScope.launch {
            sdk.conversations.collect { _ -> rebuild() }
        }
    }

    private fun observeMessageEvents() {
        viewModelScope.launch { sdk.messageReceived.collect { _ -> rebuild() } }
        viewModelScope.launch { sdk.inviteReceived.collect { rebuild() } }
        viewModelScope.launch {
            sdk.groupDeleted.collect { conversationId ->
                categoryCache.remove(conversationId)
                tickets.update { it?.filter { snap -> snap.conversationId != conversationId } }
            }
        }
        viewModelScope.launch {
            sdk.memberLeft.collect { (conversationId, _) ->
                // Re-derive: a member leaving may change isSupportConversation on the agent side.
                rebuild()
            }
        }
    }

    private suspend fun rebuild() {
        runChatCall("SupportTicketListVM: rebuild failed") {
            val localPublicKey = sdk.identity.value?.publicKey
            val supportConvs =
                sdk.conversations.value
                    .map(ChatConversation::from)
                    .filter {
                        SupportChatConstants.isSupportConversation(
                            displayName = it.displayName,
                            participantIds = it.participantIds,
                            localPublicKey = localPublicKey,
                        )
                    }.sortedByDescending { it.lastMessageTimestamp ?: 0L }

            val snapshots =
                supportConvs.map { conv ->
                    val category = categoryCache.getOrPut(conv.id) { fetchCategory(conv.id) }
                    TicketSnapshot(
                        conversationId = conv.id,
                        category = category,
                        lastMessage = stripBotPrefix(conv.lastMessage),
                        lastMessageTimestamp = conv.lastMessageTimestamp,
                        unreadCount = conv.unreadCount,
                    )
                }
            tickets.value = snapshots
        }
        if (tickets.value == null) tickets.value = emptyList()
    }

    private suspend fun fetchCategory(conversationId: String): SupportCategory? {
        var result: SupportCategory? = null
        runChatCall("SupportTicketListVM: fetchCategory failed for $conversationId") {
            result =
                sdk
                    .getMessages(conversationId)
                    .map(ChatMessage::from)
                    .firstNotNullOfOrNull { SupportChatConstants.parseCategoryMarker(it.content) }
        }
        return result
    }

    private fun stripBotPrefix(message: String?): String? =
        message?.removePrefix(SupportChatConstants.BOT_PREFIX)

    private fun onNewTicket() {
        navigationRouter.forward(SupportChatArgs())
    }

    private fun onBack() = navigationRouter.back()

    private fun onCloseRequest(ticket: TicketSnapshot) {
        closeTarget.value = ticket
    }

    private fun onCloseDismiss() {
        closeTarget.value = null
    }

    private fun onCloseConfirm(ticket: TicketSnapshot) {
        closeTarget.value = null
        viewModelScope.launch {
            runChatCall("SupportTicketListVM: send leave notice failed") {
                val notice = application.getString(R.string.support_chat_leave_notice)
                sdk.sendMessage(
                    ticket.conversationId,
                    "${SupportChatConstants.BOT_PREFIX}$notice",
                )
            }
            runChatCall("SupportTicketListVM: removeConversation failed") {
                sdk.removeConversation(ticket.conversationId)
            }
            categoryCache.remove(ticket.conversationId)
            tickets.update { it?.filter { snap -> snap.conversationId != ticket.conversationId } }
        }
    }

    private data class TicketSnapshot(
        val conversationId: String,
        val category: SupportCategory?,
        val lastMessage: String?,
        val lastMessageTimestamp: Long?,
        val unreadCount: Int,
    )

    private fun TicketSnapshot.toItem(): SupportTicketItem {
        val categoryLabel: StringResource =
            category
                ?.displayNameRes
                ?.let { stringRes(it) }
                ?: stringRes(R.string.support_ticket_default_label)
        return SupportTicketItem(
            conversationId = conversationId,
            categoryLabel = categoryLabel,
            lastMessage = lastMessage?.let { stringRes(it) },
            timeLabel = lastMessageTimestamp?.let { formatRelativeTime(it) },
            unreadCount = unreadCount,
            onClick = { navigationRouter.forward(SupportChatArgs(conversationId = conversationId)) },
            onCloseSwipe = { onCloseRequest(this) },
        )
    }
}
