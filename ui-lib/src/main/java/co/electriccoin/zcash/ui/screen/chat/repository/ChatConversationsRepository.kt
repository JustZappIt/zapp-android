// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.repository

import co.electriccoin.zcash.ui.screen.chat.model.ChatConversation
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Single source of truth for the conversation list and global connection state, owning the
 * one cache that both the list and room screens read. All conversation-level SDK events
 * (incoming message, invite, group rename/delete, member add/leave) are reduced here exactly
 * once, with the moderation block filter applied once, so the two view models can no longer
 * drift their caches or duplicate the reducer logic.
 */
interface ChatConversationsRepository {
    /** The conversation cache. `null` until the first refresh completes (loading state). */
    val conversations: StateFlow<List<ChatConversation>?>

    /** Local identity public key, or `null` before the chat identity has derived. */
    val localPublicKey: StateFlow<String?>

    val isOnline: StateFlow<Boolean>
    val peerCount: StateFlow<Int>
    val dhtHealth: StateFlow<String>

    /** Conversation ids removed upstream (group deleted) — the room uses this to navigate back. */
    val conversationDeleted: SharedFlow<String>

    /** Re-pulls the conversation list from the SDK and replaces the cache. */
    suspend fun refresh()

    /** Zeroes the unread count of [conversationId] and emits its read receipt when enabled. */
    fun markConversationRead(conversationId: String)

    /**
     * Marks [conversationId] as the on-screen room (or `null` when none) so incoming messages
     * for the open conversation don't inflate its unread count.
     */
    fun setActiveConversation(conversationId: String?)

    /** Clears the active conversation, but only while [conversationId] still holds it. */
    fun releaseActiveConversation(conversationId: String)

    /** Immediately updates the cached preview/order for a locally queued outgoing [message]. */
    fun recordOutgoingMessage(message: ChatMessage)

    /** Applies a group-name change to the cached conversation. */
    fun renameConversation(conversationId: String, newName: String)

    /** Leaves [conversationId] upstream and removes it from the cache. */
    suspend fun leaveConversation(conversationId: String)

    /** A single cached conversation, kept live as the cache reduces events. */
    fun conversation(conversationId: String): Flow<ChatConversation?>

    companion object {
        /**
         * Legacy placeholder stored as a conversation's last message when it is a media
         * attachment. Kept so previews cached before the per-type sentinels still resolve.
         */
        const val MEDIA_PLACEHOLDER_SENTINEL = "[Media]"

        /** Per-type last-message placeholders; the list maps each to a localized label. */
        const val PHOTO_PLACEHOLDER_SENTINEL = "[Photo]"
        const val VIDEO_PLACEHOLDER_SENTINEL = "[Video]"
        const val FILE_PLACEHOLDER_SENTINEL = "[File]"
        const val LOCATION_PLACEHOLDER_SENTINEL = "[Location]"

        /** Written by the JS core into cold-loaded conversation previews for GIF messages. */
        const val GIF_PLACEHOLDER_SENTINEL = "[GIF]"

        /** In-chat payment last-message placeholders; the list maps each to a localized label. */
        const val PAYMENT_PLACEHOLDER_SENTINEL = "[Payment]"
        const val PAYMENT_REQUEST_PLACEHOLDER_SENTINEL = "[PaymentRequest]"
    }
}
