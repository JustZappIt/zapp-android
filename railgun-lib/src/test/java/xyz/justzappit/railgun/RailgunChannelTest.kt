// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.railgun

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class RailgunChannelTest {
    private val posted = mutableListOf<String>()
    private val events = mutableListOf<RailgunEvent>()
    private val channel = RailgunChannel({ posted += it }, { events += it })

    @Test
    fun aReplyAnswersItsOwnCall() =
        runTest {
            val first = async { channel.call(RailgunMethod.REVERSE_COST, EMPTY) }
            val second = async { channel.call(RailgunMethod.REVERSE_COST, EMPTY) }
            runCurrent()

            channel.receive("""{"id":${idOf(1)},"result":"second"}""")
            channel.receive("""{"id":${idOf(0)},"result":"first"}""")

            assertEquals(JsonPrimitive("first"), first.await())
            assertEquals(JsonPrimitive("second"), second.await())
        }

    @Test
    fun aCallWithoutAReplyTimesOutAsAnError() =
        runTest {
            val call = async { runCatching { channel.call(RailgunMethod.REVERSE_COST, EMPTY) } }

            advanceTimeBy(RailgunMethod.REVERSE_COST.timeout + 1.seconds)

            val timeout = assertFailsWith<RailgunException.Timeout> { call.await().getOrThrow() }
            assertEquals("REVERSE_COST timed out", timeout.message)
        }

    @Test
    fun aReplyItCannotReadFailsTheCallAtOnce() =
        runTest {
            val call = async { runCatching { channel.call(RailgunMethod.REFRESH, EMPTY) } }
            runCurrent()

            channel.receive("""{"id":${idOf(0)},"error":"a bare string"}""")

            assertFailsWith<RailgunException.Protocol> { call.await().getOrThrow() }
        }

    @Test
    fun closingFailsWhatWaitsAndWhatComesAfter() =
        runTest {
            val call = async { runCatching { channel.call(RailgunMethod.REFRESH, EMPTY) } }
            runCurrent()

            channel.close(RailgunException.Disconnected("the renderer stopped"))
            channel.receive("""{"id":${idOf(0)},"result":"late"}""")

            assertFalse(channel.isOpen)
            assertFailsWith<RailgunException.Disconnected> { call.await().getOrThrow() }
            assertFailsWith<RailgunException.Disconnected> { channel.call(RailgunMethod.REFRESH, EMPTY) }
            assertEquals(1, posted.size)
        }

    @Test
    fun theHelloOnlyCountsOnItsOwnChannel() =
        runTest {
            val other = RailgunChannel({}, {})
            other.receive("""{"event":"ready","data":null}""")

            assertFailsWith<RailgunException.Timeout> { channel.awaitReady(1.seconds) }

            channel.receive("""{"event":"ready","data":null}""")
            channel.awaitReady(1.seconds)
        }

    @Test
    fun closingBeforeTheHelloFailsTheLoad() =
        runTest {
            channel.close(RailgunException.Disconnected("the page did not load"))

            assertFailsWith<RailgunException.Disconnected> { channel.awaitReady(1.seconds) }
        }

    @Test
    fun eventsGoToTheirListener() {
        channel.receive("""{"event":"proof","data":{"progress":0.5,"status":"proving"}}""")

        assertEquals(listOf<RailgunEvent>(RailgunEvent.Proof(0.5f, "proving")), events)
        assertTrue(channel.isOpen)
    }

    private fun idOf(request: Int): Long {
        val message = Json.parseToJsonElement(posted[request]).jsonObject
        return message.getValue("id").jsonPrimitive.long
    }

    private companion object {
        val EMPTY = JsonObject(emptyMap())
    }
}
