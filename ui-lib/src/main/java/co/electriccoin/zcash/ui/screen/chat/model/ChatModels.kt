// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.model

import xyz.justzappit.zappmessaging.models.ZMConversation
import xyz.justzappit.zappmessaging.models.ZMIdentity
import xyz.justzappit.zappmessaging.models.ZMMessage

internal const val MAX_DISPLAY_NAME_LENGTH = 100

internal fun String.capDisplayName(): String =
    if (length > MAX_DISPLAY_NAME_LENGTH) take(MAX_DISPLAY_NAME_LENGTH) else this

internal fun String.normalizeMessagingPublicKey(): String = trim().lowercase().removePrefix("0x")

internal fun List<ChatContact>.byPublicKey(): Map<String, ChatContact> =
    associateBy { it.publicKey.normalizeMessagingPublicKey() }

internal fun ChatConversation.resolveDisplayName(contactsByPublicKey: Map<String, ChatContact>): String {
    if (type == ConversationType.GROUP) return displayName
    // participantIds normally holds just the peer, but legacy rows can carry our own key too;
    // scan every key for a saved alias so a self key at index 0 can't shadow the peer's local name.
    val alias =
        participantIds.firstNotNullOfOrNull { participantId ->
            contactsByPublicKey[participantId.normalizeMessagingPublicKey()]?.name?.takeIf { it.isNotBlank() }
        }
    val peerKey = participantIds.firstOrNull()?.normalizeMessagingPublicKey().orEmpty()
    return alias
        ?: displayName.takeIf { it.isNotBlank() }
        ?: peerKey.take(KEY_PREVIEW_LENGTH)
}

internal fun ChatMessage.resolveSenderName(contactsByPublicKey: Map<String, ChatContact>): ChatMessage {
    if (isFromMe) return this
    val alias = senderId?.normalizeMessagingPublicKey()?.let(contactsByPublicKey::get)?.name
    return alias?.takeIf { it.isNotBlank() }?.let { copy(senderName = it) } ?: this
}

internal fun List<ChatMessage>.resolveSenderNames(
    contactsByPublicKey: Map<String, ChatContact>
): List<ChatMessage> {
    val messagesById = associateBy(ChatMessage::id)
    return map { message ->
        val resolvedMessage = message.resolveSenderName(contactsByPublicKey)
        val repliedTo = message.replyToId?.let(messagesById::get)?.takeUnless { it.isFromMe }
        val replyAlias =
            repliedTo
                ?.senderId
                ?.normalizeMessagingPublicKey()
                ?.let(contactsByPublicKey::get)
                ?.name
                ?.takeIf { it.isNotBlank() }
        replyAlias?.let { resolvedMessage.copy(replyToSenderName = it) } ?: resolvedMessage
    }
}

enum class ConversationType {
    DIRECT,
    GROUP
}

data class ChatIdentity(
    val publicKey: String,
    val displayName: String
) {
    companion object {
        fun from(zmIdentity: ZMIdentity) =
            ChatIdentity(
                publicKey = zmIdentity.publicKey,
                displayName = zmIdentity.displayName.capDisplayName()
            )
    }
}

data class ChatConversation(
    val id: String,
    val type: ConversationType,
    val displayName: String,
    val lastMessage: String? = null,
    val lastMessageTimestamp: Long? = null,
    val participantIds: List<String> = emptyList(),
    val isOwner: Boolean = false,
    val unreadCount: Int = 0
) {
    companion object {
        fun from(zmConv: ZMConversation) =
            ChatConversation(
                id = zmConv.id,
                type =
                    when (zmConv.type) {
                        xyz.justzappit.zappmessaging.models.ConversationType.GROUP -> ConversationType.GROUP
                        else -> ConversationType.DIRECT
                    },
                displayName = zmConv.displayName.capDisplayName(),
                lastMessage = zmConv.lastMessage,
                lastMessageTimestamp = zmConv.lastMessageTimestamp,
                participantIds = zmConv.participantIds,
                isOwner = zmConv.isOwner ?: false,
                unreadCount = zmConv.unreadCount ?: 0
            )
    }
}

enum class MessageStatus {
    SENDING,
    QUEUED,
    SENT,
    DELIVERED,
    FAILED,
    READ;

    /**
     * Delivery updates can cross the IPC response that created the message row. Keep the
     * user-visible state monotonic so a late queued/sending event cannot undo relay acceptance,
     * recipient delivery, or read.
     * A failure is always surfaced because it can arrive asynchronously after local storage.
     */
    fun advanceTo(next: MessageStatus): MessageStatus =
        when {
            this == READ -> READ
            this == FAILED -> FAILED
            next == FAILED || next == READ -> next
            this == DELIVERED || next == DELIVERED -> DELIVERED
            this == SENT -> SENT
            next == SENT -> SENT
            this == QUEUED && next == SENDING -> QUEUED
            else -> next
        }
}

private fun outgoingStatusOf(rawStatus: String?): MessageStatus =
    when (rawStatus) {
        "queued" -> MessageStatus.QUEUED
        "sent" -> MessageStatus.SENT
        "delivered" -> MessageStatus.DELIVERED
        "read" -> MessageStatus.READ
        else -> MessageStatus.SENT
    }

data class ChatMessage(
    val id: String,
    val conversationId: String,
    val content: String,
    val contentType: String? = "text/plain",
    val senderName: String? = null,
    val senderId: String? = null,
    val isFromMe: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
    val mediaId: String? = null,
    val mediaSize: Int? = null,
    val mediaWidth: Int? = null,
    val mediaHeight: Int? = null,
    val thumbnailData: String? = null,
    val mediaLocalPath: String? = null,
    val mediaTransferState: String? = null,
    val status: MessageStatus? = null,
    val replyToId: String? = null,
    val replyToSenderName: String? = null,
    val replyToContent: String? = null
) {
    fun advanceStatus(next: MessageStatus): ChatMessage = copy(status = status?.advanceTo(next) ?: next)

    /** Read state is retained in the model but hidden when reciprocal receipts are disabled. */
    fun withReadReceiptVisibility(isVisible: Boolean): ChatMessage =
        if (!isVisible && status == MessageStatus.READ) copy(status = MessageStatus.DELIVERED) else this

    companion object {
        fun pendingText(
            id: String,
            conversationId: String,
            content: String,
            replyToId: String?,
            replyToSenderName: String?,
            replyToContent: String?,
        ) =
            ChatMessage(
                id = id,
                conversationId = conversationId,
                content = content,
                isFromMe = true,
                status = MessageStatus.SENDING,
                replyToId = replyToId,
                replyToSenderName = replyToSenderName,
                replyToContent = replyToContent,
            )

        fun from(zmMsg: ZMMessage) =
            ChatMessage(
                id = zmMsg.id,
                conversationId = zmMsg.conversationId,
                content = zmMsg.content,
                contentType = zmMsg.contentType,
                senderName = zmMsg.senderName?.capDisplayName(),
                senderId = zmMsg.senderId,
                isFromMe = zmMsg.isFromMe,
                timestamp = zmMsg.timestamp,
                mediaId = zmMsg.mediaId,
                mediaSize = zmMsg.mediaSize,
                mediaWidth = zmMsg.mediaWidth,
                mediaHeight = zmMsg.mediaHeight,
                thumbnailData = zmMsg.thumbnailData,
                mediaLocalPath = zmMsg.mediaLocalPath,
                mediaTransferState = zmMsg.mediaTransferState?.name?.lowercase(),
                status = if (zmMsg.isFromMe) outgoingStatusOf(zmMsg.status) else null,
                replyToId = zmMsg.replyToId,
                replyToSenderName = zmMsg.replyToSenderName,
                replyToContent = zmMsg.replyToContent,
            )
    }
}

/**
 * A saved chat contact, built by [co.electriccoin.zcash.ui.screen.chat.repository.ChatContactsRepository]
 * from its address-book row. [address] is the primary (unified ZEC) wallet address; [walletAddresses]
 * holds the per-chain extras.
 */
data class ChatContact(
    val publicKey: String,
    val name: String,
    val address: String? = null,
    val walletAddresses: Map<String, String> = emptyMap(),
    val isBlocked: Boolean = false,
)

private const val KEY_PREVIEW_LENGTH = 8
