// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.grouplink.model

import co.electriccoin.zcash.ui.screen.grouplink.model.GroupInviteEvent.Answered
import co.electriccoin.zcash.ui.screen.grouplink.model.GroupInviteEvent.CancelTapped
import co.electriccoin.zcash.ui.screen.grouplink.model.GroupInviteEvent.FlagOff
import co.electriccoin.zcash.ui.screen.grouplink.model.GroupInviteEvent.Inspected
import co.electriccoin.zcash.ui.screen.grouplink.model.GroupInviteEvent.JoinTapped
import co.electriccoin.zcash.ui.screen.grouplink.model.GroupInviteEvent.Missing
import co.electriccoin.zcash.ui.screen.grouplink.model.GroupInviteEvent.NotNowTapped
import co.electriccoin.zcash.ui.screen.grouplink.model.GroupInviteEvent.Refused
import co.electriccoin.zcash.ui.screen.grouplink.model.GroupInviteEvent.SendFailed
import co.electriccoin.zcash.ui.screen.grouplink.model.GroupInviteEvent.Updated
import co.electriccoin.zcash.ui.screen.grouplink.model.GroupInvitePhase.Dismissed
import co.electriccoin.zcash.ui.screen.grouplink.model.GroupInvitePhase.Failed
import co.electriccoin.zcash.ui.screen.grouplink.model.GroupInvitePhase.Joined
import co.electriccoin.zcash.ui.screen.grouplink.model.GroupInvitePhase.Preview
import co.electriccoin.zcash.ui.screen.grouplink.model.GroupInvitePhase.Reading
import co.electriccoin.zcash.ui.screen.grouplink.model.GroupInvitePhase.Requesting
import co.electriccoin.zcash.ui.screen.grouplink.model.GroupInvitePhase.Waiting
import xyz.justzappit.zappmessaging.models.ZMGroupJoinRequestStatus
import xyz.justzappit.zappmessaging.models.ZMGroupJoinResult
import xyz.justzappit.zappmessaging.models.ZMGroupJoinStatus
import xyz.justzappit.zappmessaging.models.ZMGroupJoinUpdate
import xyz.justzappit.zappmessaging.models.ZMGroupLinkInspectStatus
import xyz.justzappit.zappmessaging.models.ZMGroupLinkInspection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GroupInviteMachineTest {
    private fun step(
        phase: GroupInvitePhase,
        event: GroupInviteEvent,
    ) = GroupInviteMachine.reduce(phase, event)

    private fun inspected(
        status: ZMGroupLinkInspectStatus,
        name: String? = NAME,
    ) = Inspected(ZMGroupLinkInspection(status, nameHint = name, linkId = LINK_ID))

    private fun answered(
        status: ZMGroupJoinRequestStatus,
        conversationId: String? = null,
    ) = Answered(ZMGroupJoinResult(status, LINK_ID, conversationId))

    private fun updated(
        status: ZMGroupJoinStatus,
        linkId: String = LINK_ID,
        conversationId: String? = null,
    ) = Updated(ZMGroupJoinUpdate(linkId, status, conversationId))

    private val preview = Preview(NAME, LINK_ID)
    private val requesting = Requesting(NAME, LINK_ID)
    private val waiting = Waiting(NAME, LINK_ID, withOwner = false)

    @Test
    fun `reading a good link shows the preview and keeps the link`() {
        val next = step(Reading, inspected(ZMGroupLinkInspectStatus.OK))
        assertEquals(preview, next.phase)
        assertFalse(next.dropLink, "the preview contacted nobody; the link is still needed")
    }

    @Test
    fun `reading a bad link fails once and deletes it`() {
        mapOf(
            ZMGroupLinkInspectStatus.EXPIRED to GroupInviteFailure.EXPIRED,
            ZMGroupLinkInspectStatus.MALFORMED to GroupInviteFailure.UNREADABLE,
            ZMGroupLinkInspectStatus.UNSUPPORTED_VERSION to GroupInviteFailure.NEEDS_UPDATE,
            ZMGroupLinkInspectStatus.NEWER_FORMAT to GroupInviteFailure.NEEDS_UPDATE,
        ).forEach { (status, reason) ->
            val next = step(Reading, inspected(status))
            assertEquals(Failed(reason), next.phase, status.name)
            assertTrue(next.dropLink, status.name)
        }
    }

    @Test
    fun `refused, flag off and missing each land somewhere`() {
        assertEquals(Failed(GroupInviteFailure.UNREADABLE), step(Reading, Refused).phase)
        assertEquals(Failed(GroupInviteFailure.COMING_SOON), step(Reading, FlagOff).phase)
        val missing = step(Reading, Missing)
        assertEquals(Dismissed, missing.phase)
        assertFalse(missing.dropLink, "a token that is gone has nothing left to delete")
    }

    @Test
    fun `not now closes and deletes the link`() {
        for (from in listOf(Reading, preview)) {
            val next = step(from, NotNowTapped)
            assertEquals(Dismissed, next.phase)
            assertTrue(next.dropLink)
        }
    }

    @Test
    fun `join asks and keeps the link until the SDK holds the request`() {
        val next = step(preview, JoinTapped)
        assertEquals(requesting, next.phase)
        assertFalse(next.dropLink)
    }

    @Test
    fun `a request that left the device moves to waiting and deletes the secret`() {
        for (status in listOf(ZMGroupJoinRequestStatus.REQUESTED, ZMGroupJoinRequestStatus.ALREADY_REQUESTED)) {
            val next = step(requesting, answered(status))
            assertEquals(waiting, next.phase, status.name)
            assertTrue(next.dropLink, status.name)
        }
    }

    @Test
    fun `a request that did not leave the device goes back to the preview with a note and keeps the link`() {
        val next = step(requesting, SendFailed)
        assertEquals(Preview(NAME, LINK_ID, sendFailed = true), next.phase)
        assertFalse(next.dropLink)
        assertEquals(requesting, step(next.phase, JoinTapped).phase, "and the person can try again")
    }

    @Test
    fun `already a member opens the group`() {
        val next = step(requesting, answered(ZMGroupJoinRequestStatus.ALREADY_MEMBER, "g1"))
        assertEquals(Joined(NAME, "g1", alreadyMember = true), next.phase)
        assertTrue(next.dropLink)
    }

    @Test
    fun `the SDK refusing to send is explained`() {
        assertEquals(
            Failed(GroupInviteFailure.EXPIRED),
            step(requesting, answered(ZMGroupJoinRequestStatus.EXPIRED)).phase,
        )
        assertEquals(
            Failed(GroupInviteFailure.UNREADABLE),
            step(requesting, answered(ZMGroupJoinRequestStatus.MALFORMED)).phase,
        )
        assertEquals(
            Failed(GroupInviteFailure.NEEDS_UPDATE),
            step(requesting, answered(ZMGroupJoinRequestStatus.NEWER_FORMAT)).phase,
        )
    }

    @Test
    fun `every answer from the owner while waiting`() {
        val cases =
            mapOf(
                ZMGroupJoinStatus.WAITING to waiting,
                ZMGroupJoinStatus.PENDING_APPROVAL to Waiting(NAME, LINK_ID, withOwner = true),
                ZMGroupJoinStatus.JOINED to Joined(NAME, "g1", alreadyMember = false),
                ZMGroupJoinStatus.INACTIVE to Failed(GroupInviteFailure.INACTIVE),
                ZMGroupJoinStatus.EXPIRED to Failed(GroupInviteFailure.EXPIRED),
                ZMGroupJoinStatus.FULL to Failed(GroupInviteFailure.FULL),
                ZMGroupJoinStatus.DECLINED to Failed(GroupInviteFailure.DECLINED),
                ZMGroupJoinStatus.CANCELLED to Dismissed,
                ZMGroupJoinStatus.UNKNOWN to waiting,
            )
        cases.forEach { (status, expected) ->
            val conversationId = if (status == ZMGroupJoinStatus.JOINED) "g1" else null
            assertEquals(expected, step(waiting, updated(status, conversationId = conversationId)).phase, status.name)
        }
    }

    @Test
    fun `an answer can arrive before the request call returns`() {
        assertEquals(
            Joined(NAME, "g1", alreadyMember = false),
            step(requesting, updated(ZMGroupJoinStatus.JOINED, conversationId = "g1")).phase,
        )
    }

    @Test
    fun `answers about another link are ignored`() {
        assertEquals(waiting, step(waiting, updated(ZMGroupJoinStatus.DECLINED, linkId = "other")).phase)
        assertEquals(requesting, step(requesting, updated(ZMGroupJoinStatus.JOINED, linkId = "other")).phase)
        assertEquals(preview, step(preview, updated(ZMGroupJoinStatus.WAITING, linkId = "other")).phase)
    }

    @Test
    fun `tapping a link already in flight shows where it stands`() {
        assertEquals(
            Waiting(NAME, LINK_ID, withOwner = true),
            step(preview, updated(ZMGroupJoinStatus.PENDING_APPROVAL)).phase,
        )
        assertEquals(
            Joined(NAME, "g1", alreadyMember = true),
            step(preview, updated(ZMGroupJoinStatus.JOINED, conversationId = "g1")).phase,
        )
        // An earlier request through this link ended; this tap is a fresh try.
        assertEquals(preview, step(preview, updated(ZMGroupJoinStatus.DECLINED)).phase)
    }

    @Test
    fun `cancel closes a waiting request`() {
        val next = step(waiting, CancelTapped)
        assertEquals(Dismissed, next.phase)
    }

    @Test
    fun `end states stay put`() {
        val ends = listOf(Joined(NAME, "g1", false), Failed(GroupInviteFailure.FULL), Dismissed)
        val events = listOf(JoinTapped, NotNowTapped, SendFailed, CancelTapped, updated(ZMGroupJoinStatus.WAITING))
        for (end in ends) for (event in events) assertEquals(end, step(end, event).phase)
    }

    @Test
    fun `the name from the owner's answer wins over the hint`() {
        val update = Updated(ZMGroupJoinUpdate(LINK_ID, ZMGroupJoinStatus.JOINED, "g1", nameHint = "Renamed"))
        assertEquals(Joined("Renamed", "g1", alreadyMember = false), step(waiting, update).phase)
    }

    /**
     * Process death between any two steps. Only two things survive: the stored link (kept unless a
     * step dropped it) and the SDK's request. Relaunching from them must land where the person was,
     * or on the chat list's waiting row, never on a second request or a lost one.
     */
    @Test
    fun `process death between any two steps recovers from what was stored`() {
        val journey =
            listOf(
                inspected(ZMGroupLinkInspectStatus.OK),
                JoinTapped,
                answered(ZMGroupJoinRequestStatus.REQUESTED),
                updated(ZMGroupJoinStatus.PENDING_APPROVAL),
                updated(ZMGroupJoinStatus.JOINED, conversationId = "g1"),
            )
        var phase: GroupInvitePhase = Reading
        var linkStored = true
        var sdkStatus: ZMGroupJoinStatus? = null
        for (event in journey) {
            val next = step(phase, event)
            phase = next.phase
            if (next.dropLink) linkStored = false
            when (event) {
                is Answered -> sdkStatus = ZMGroupJoinStatus.WAITING
                is Updated -> sdkStatus = event.update.status
                else -> Unit
            }

            // Relaunch here.
            if (linkStored) {
                var relaunched = step(Reading, inspected(ZMGroupLinkInspectStatus.OK)).phase
                sdkStatus?.let { relaunched = step(relaunched, updated(it, conversationId = "g1")).phase }
                val expected =
                    when (sdkStatus) {
                        null -> preview
                        ZMGroupJoinStatus.JOINED -> Joined(NAME, "g1", alreadyMember = true)
                        else -> Waiting(NAME, LINK_ID, withOwner = sdkStatus == ZMGroupJoinStatus.PENDING_APPROVAL)
                    }
                assertEquals(expected, relaunched, "after $event")
            } else {
                // No preview reopens; the SDK's record is what the chat list shows.
                assertTrue(sdkStatus != null, "after $event the link is gone, so the SDK must hold the request")
            }
        }
        assertEquals(Joined(NAME, "g1", alreadyMember = false), phase)
    }

    /** The one gap: killed after the SDK queued the request but before the answer came back. */
    @Test
    fun `killed while requesting, the same link asks again and the SDK says it already has it`() {
        val relaunched = step(Reading, inspected(ZMGroupLinkInspectStatus.OK)).phase
        val asking = step(relaunched, JoinTapped).phase
        val next = step(asking, answered(ZMGroupJoinRequestStatus.ALREADY_REQUESTED))
        assertEquals(waiting, next.phase)
        assertTrue(next.dropLink)
    }

    private companion object {
        const val NAME = "Hiking Crew"
        const val LINK_ID = "c0ffee"
    }
}
