package co.electriccoin.zcash.ui.screen.chat.model

/**
 * Ordering and merge rules for a room's in-memory message list.
 *
 * Messages do not arrive in order: blind-peer catch-up replays a peer's older
 * messages after newer live ones, and the initial history load races the live
 * message stream. Every mutation of the list goes through these helpers so it
 * stays deduplicated by id and chronologically sorted — which is what keeps
 * LazyColumn keys unique and date separators from repeating mid-list.
 *
 * Ordering is by sender timestamp; equal timestamps keep their existing
 * relative order (the sort is stable), matching the worklet's persisted order.
 */

fun List<ChatMessage>.sortedChronologically(): List<ChatMessage> = sortedBy { it.timestamp }

/** Adds [message] at its chronological position, unless its id is already present. */
fun List<ChatMessage>.plusMessage(message: ChatMessage): List<ChatMessage> =
    if (any { it.id == message.id }) this else (this + message).sortedChronologically()

/**
 * Merges the persisted [history] into the live list. Rows already present win
 * by id: they carry state the disk snapshot may not have yet (optimistic sends,
 * statuses advanced by receipts, completed media downloads). History only
 * contributes rows the list hasn't seen.
 */
fun List<ChatMessage>.mergedWithHistory(history: List<ChatMessage>): List<ChatMessage> =
    (this + history)
        .distinctBy { it.id }
        .sortedChronologically()

/** Replaces a media placeholder or persisted twin while retaining receipt evidence. */
fun List<ChatMessage>.reconciledMediaMessage(message: ChatMessage): List<ChatMessage> {
    val previous = firstOrNull { it.id == message.id }
    val status =
        when (previous?.status) {
            MessageStatus.READ, MessageStatus.DELIVERED, MessageStatus.SENT -> {
                message.status?.let { previous.status.advanceTo(it) } ?: previous.status
            }

            else -> {
                message.status
            }
        }
    return (filterNot { it.id == message.id } + message.copy(status = status)).sortedChronologically()
}

/** Upload and download evidence for the same content hash are independent. */
val ChatMessage.mediaTransferKey: String
    get() = "${if (isFromMe) "upload" else "download"}:$mediaId"

val ChatMessage.canRetryMedia: Boolean
    get() = mediaTransferState in setOf("failed", "queued_socket", "queued", "waiting_peer")
