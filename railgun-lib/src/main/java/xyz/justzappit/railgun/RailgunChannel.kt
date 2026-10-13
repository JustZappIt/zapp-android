// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.railgun

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import xyz.justzappit.railgun.RailgunProtocol.Message
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration

/** One page's end of the port: requests out, replies matched back to them. Everything fails once it closes. */
internal class RailgunChannel(
    private val post: suspend (String) -> Unit,
    private val onEvent: (RailgunEvent) -> Unit,
) {
    private val ids = AtomicLong()
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonElement>>()
    private val ready = CompletableDeferred<Unit>()

    @Volatile
    private var closedBy: RailgunException? = null

    val isOpen: Boolean get() = closedBy == null

    /** Waits for the page's hello; only this channel's own port can carry it. */
    suspend fun awaitReady(timeout: Duration) {
        withTimeoutOrNull(timeout) { ready.await() } ?: throw RailgunException.Timeout("loading the page")
    }

    suspend fun call(
        method: RailgunMethod,
        params: JsonElement
    ): JsonElement {
        val id = ids.incrementAndGet()
        val reply = CompletableDeferred<JsonElement>()
        pending[id] = reply
        try {
            closedBy?.let { throw it.forCaller() }
            post(RailgunProtocol.request(id, method, params))
            return withTimeoutOrNull(method.timeout) { reply.await() } ?: throw RailgunException.Timeout(method.name)
        } finally {
            pending.remove(id)
        }
    }

    fun receive(data: String?) {
        when (val message = RailgunProtocol.decode(data)) {
            Message.Ready -> ready.complete(Unit)
            is Message.Event -> onEvent(message.event)
            is Message.Reply -> pending.remove(message.id)?.complete(message.result)
            is Message.Failure -> pending.remove(message.id)?.completeExceptionally(message.error)
            null -> Unit
        }
    }

    fun close(reason: RailgunException) {
        if (closedBy == null) closedBy = reason
        ready.completeExceptionally(reason.forCaller())
        pending.values.forEach { it.completeExceptionally(reason.forCaller()) }
    }

    // Each caller gets its own: coroutines add to what they throw, so one instance can't go to several.
    private fun RailgunException.forCaller() = RailgunException.Disconnected(message ?: "the page closed", this)
}
