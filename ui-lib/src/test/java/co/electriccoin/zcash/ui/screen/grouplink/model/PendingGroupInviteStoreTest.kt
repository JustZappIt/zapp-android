// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.grouplink.model

import co.electriccoin.zcash.ui.screen.grouplink.model.GroupInviteLinksTest.Companion.LINK
import co.electriccoin.zcash.ui.screen.grouplink.model.GroupInviteLinksTest.Companion.PAYLOAD
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PendingGroupInviteStoreTest {
    private val preferences = InMemoryPreferenceProvider()
    private var clock = START

    /** A fresh store over the same preferences is what the app sees after process death. */
    private fun store() = pendingInviteStore(preferences) { clock }

    private fun link(n: Int) = "https://join.justzappit.xyz/g/v1#${PAYLOAD.dropLast(1)}$n"

    @Test
    fun `holds a link across process death and hands it back for its token`() =
        runTest {
            val token = assertIs<GroupInviteIntake.Accepted>(store().put(LINK)).token

            val relaunched = store()
            assertEquals(LINK, relaunched.link(token))
            assertEquals(token, relaunched.newest())
        }

    @Test
    fun `the token carries nothing of the link and neither does toString`() =
        runTest {
            val token = assertIs<GroupInviteIntake.Accepted>(store().put(LINK)).token
            assertFalse(token.contains(PAYLOAD.take(8)))
            val stored = preferences.raw(PendingGroupInviteStore.PREF_KEY)!!
            assertTrue(stored.contains(PAYLOAD), "the link is stored, in the encrypted preferences")
            assertFalse(PendingGroupInvite(token, LINK, clock).toString().contains(PAYLOAD))
        }

    @Test
    fun `stores the canonical form, without the query`() =
        runTest {
            val store = store()
            val tracked = "https://join.justzappit.xyz/g/v1?utm=1#$PAYLOAD"
            val token = assertIs<GroupInviteIntake.Accepted>(store.put(tracked)).token
            assertEquals(LINK, store.link(token))
            assertFalse(preferences.raw(PendingGroupInviteStore.PREF_KEY)!!.contains("utm"))
        }

    @Test
    fun `refuses what is not a group link and stores nothing`() =
        runTest {
            assertEquals(GroupInviteIntake.Refused, store().put("https://gift.justzappit.xyz/c/v1#abc"))
            assertNull(preferences.raw(PendingGroupInviteStore.PREF_KEY))
        }

    @Test
    fun `a repeat tap merges into one entry and moves it to the front`() =
        runTest {
            val store = store()
            val first = assertIs<GroupInviteIntake.Accepted>(store.put(link(1))).token
            store.put(link(2))
            clock += 1000
            val again = assertIs<GroupInviteIntake.Accepted>(store.put(link(1))).token

            assertEquals(first, again)
            assertEquals(listOf(first), store.observeTokens().first().take(1))
            assertEquals(2, store.observeTokens().first().size)
        }

    @Test
    fun `keeps the newest four`() =
        runTest {
            val store = store()
            val tokens = (1..5).map { assertIs<GroupInviteIntake.Accepted>(store.put(link(it))).token }

            assertEquals(tokens.reversed().take(PendingGroupInviteStore.MAX_INVITES), store.observeTokens().first())
            assertNull(store.link(tokens.first()), "the oldest one made room")
        }

    @Test
    fun `lapses after seven days`() =
        runTest {
            val store = store()
            val token = assertIs<GroupInviteIntake.Accepted>(store.put(LINK)).token

            clock += PendingGroupInviteStore.TTL_MS
            assertEquals(LINK, store.link(token), "still held on the last day")

            clock += 1
            assertNull(store.link(token))
            assertNull(store.newest())
            assertNull(preferences.raw(PendingGroupInviteStore.PREF_KEY), "a lapsed link is deleted, not just hidden")
        }

    @Test
    fun `remove and clear delete the secret`() =
        runTest {
            val store = store()
            val a = assertIs<GroupInviteIntake.Accepted>(store.put(link(1))).token
            val b = assertIs<GroupInviteIntake.Accepted>(store.put(link(2))).token

            store.remove(a)
            assertNull(store.link(a))
            assertEquals(link(2), store.link(b))

            store.clear()
            assertNull(preferences.raw(PendingGroupInviteStore.PREF_KEY))
        }

    @Test
    fun `a record it cannot fully read is dropped rather than guessed at`() =
        runTest {
            preferences.put(
                PendingGroupInviteStore.PREF_KEY,
                """{"version":1,"invites":[{"token":"t","link":"$LINK","receivedAt":$START,"from":"newer build"}]}""",
            )
            val store = store()
            assertNull(store.link("t"))
            assertEquals(emptyList(), store.observeTokens().first())
            assertNull(preferences.raw(PendingGroupInviteStore.PREF_KEY))

            preferences.put(PendingGroupInviteStore.PREF_KEY, """{"version":2,"invites":[]}""")
            assertNull(store().newest())
        }

    @Test
    fun `observers see a link arrive and leave`() =
        runTest {
            val store = store()
            assertEquals(emptyList(), store.observeTokens().first())
            val token = assertIs<GroupInviteIntake.Accepted>(store.put(LINK)).token
            assertEquals(listOf(token), store.observeTokens().first())
            store.remove(token)
            assertNotEquals(listOf(token), store.observeTokens().first())
        }

    private companion object {
        const val START = TEST_NOW
    }
}
