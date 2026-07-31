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
