// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.grouplink

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.screen.chat.ChatRoomArgs
import co.electriccoin.zcash.ui.screen.grouplink.model.GroupInviteIntake
import co.electriccoin.zcash.ui.screen.grouplink.model.GroupInviteLinksTest.Companion.LINK
import co.electriccoin.zcash.ui.screen.grouplink.model.InMemoryPreferenceProvider
import co.electriccoin.zcash.ui.screen.grouplink.model.newest
import co.electriccoin.zcash.ui.screen.grouplink.model.pendingInviteStore
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import xyz.justzappit.zappmessaging.models.ZMGroupJoinRequestStatus
import xyz.justzappit.zappmessaging.models.ZMGroupJoinResult
import xyz.justzappit.zappmessaging.models.ZMGroupJoinStatus
import xyz.justzappit.zappmessaging.models.ZMGroupJoinUpdate
import xyz.justzappit.zappmessaging.models.ZMGroupLinkInspectStatus
import xyz.justzappit.zappmessaging.models.ZMGroupLinkInspection
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class GroupInvitePreviewVMTest {
    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val preferences = InMemoryPreferenceProvider()
    private val store = pendingInviteStore(preferences)
    private val groupLinks = FakeGroupLinks()
    private val router = mockk<NavigationRouter>(relaxed = true)

    private suspend fun TestScope.open(args: GroupInvitePreviewArgs? = null): GroupInvitePreviewVM {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val resolved = args ?: GroupInvitePreviewArgs((store.put(LINK) as GroupInviteIntake.Accepted).token)
        val vm = GroupInvitePreviewVM(resolved, store, groupLinks, router)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        advanceUntilIdle()
        return vm
    }

    private fun GroupInvitePreviewVM.title() = (state.value.title as? StringResource.ByResource)?.resource

    @Test
    fun `shows the preview with the name, having contacted nobody`() =
        runTest {
            val vm = open()
            assertEquals(R.string.group_invite_title_named, vm.title())
            assertEquals(0, groupLinks.joins, "reading a link never asks to join")
            assertNotNull(store.newest(), "the link is kept until the person answers")
        }

    @Test
    fun `join success waits, deletes the secret, and opens the group when admitted`() =
        runTest {
            groupLinks.joinResult = requested()
            val vm = open()

            vm.state.value.primary!!
                .onClick()
            advanceUntilIdle()
            assertEquals(R.string.group_invite_waiting_title, vm.title())
            assertNull(store.newest(), "the SDK holds the request now; the secret is deleted")
            assertEquals(1, groupLinks.joins)

            groupLinks.updates.emit(ZMGroupJoinUpdate(LINK_ID, ZMGroupJoinStatus.JOINED, "g1"))
            advanceUntilIdle()
            assertEquals(R.string.group_invite_joined_named, vm.title())

            vm.state.value.primary!!
                .onClick()
            verify { router.replace(ChatRoomArgs(conversationId = "g1")) }
        }

    @Test
    fun `join failure keeps the link and says so`() =
        runTest {
            groupLinks.joinResult = Result.failure(IllegalStateException("offline"))
            val vm = open()

            vm.state.value.primary!!
                .onClick()
            advanceUntilIdle()
            assertEquals(R.string.group_invite_title_named, vm.title())
            assertEquals(R.string.group_invite_send_failed, (vm.state.value.note as StringResource.ByResource).resource)
            assertNotNull(store.newest(), "nothing left the device, so the link stays")
        }

    @Test
    fun `a declined request is explained`() =
        runTest {
            groupLinks.joinResult = requested()
            val vm = open()
            vm.state.value.primary!!
                .onClick()
            advanceUntilIdle()

            groupLinks.updates.emit(ZMGroupJoinUpdate(LINK_ID, ZMGroupJoinStatus.PENDING_APPROVAL))
            advanceUntilIdle()
            val body = vm.state.value.body as StringResource.ByResource
            assertEquals(R.string.group_invite_waiting_owner, body.resource)

            groupLinks.updates.emit(ZMGroupJoinUpdate(LINK_ID, ZMGroupJoinStatus.DECLINED))
            advanceUntilIdle()
            assertEquals(R.string.group_invite_declined, vm.title())
            assertNull(vm.state.value.primary)
        }

    @Test
    fun `not now and back both delete the link and leave`() =
        runTest {
            val vm = open()
            vm.state.value.secondary!!
                .onClick()
            advanceUntilIdle()
            assertNull(store.newest())
            verify(exactly = 1) { router.back() }

            val again = open()
            again.state.value.onBack()
            advanceUntilIdle()
            assertNull(store.newest())
            verify(exactly = 2) { router.back() }
        }

    @Test
    fun `cancelling a waiting request cancels it in the SDK`() =
        runTest {
            groupLinks.joinResult = requested()
            val vm = open()
            vm.state.value.primary!!
                .onClick()
            advanceUntilIdle()

            vm.state.value.secondary!!
                .onClick()
            advanceUntilIdle()
            assertEquals(listOf(LINK_ID), groupLinks.cancelled)
            verify { router.back() }
        }

    @Test
    fun `already a member opens the group without asking again`() =
        runTest {
            groupLinks.joinResult =
                Result.success(ZMGroupJoinResult(ZMGroupJoinRequestStatus.ALREADY_MEMBER, LINK_ID, "g1"))
            val vm = open()
            vm.state.value.primary!!
                .onClick()
            advanceUntilIdle()
            assertEquals(R.string.group_invite_already_member, vm.title())
        }

    @Test
    fun `a link already in flight opens on its waiting state`() =
        runTest {
            groupLinks.status = listOf(ZMGroupJoinUpdate(LINK_ID, ZMGroupJoinStatus.PENDING_APPROVAL))
            val vm = open()
            assertEquals(R.string.group_invite_waiting_title, vm.title())
            assertEquals(0, groupLinks.joins)
        }

    @Test
    fun `a refused link, a lapsed token and the flag off each say something or leave`() =
        runTest {
            assertEquals(R.string.group_invite_unreadable, open(GroupInvitePreviewArgs(token = null)).title())
            assertEquals(R.string.group_invite_coming_soon, open(GroupInvitePreviewArgs(comingSoon = true)).title())

            open(GroupInvitePreviewArgs(token = "gone"))
            verify { router.back() }
        }

    @Test
    fun `an expired link fails before any request and deletes the link`() =
        runTest {
            groupLinks.inspection = ZMGroupLinkInspection(ZMGroupLinkInspectStatus.EXPIRED, linkId = LINK_ID)
            val vm = open()
            assertEquals(R.string.group_invite_expired, vm.title())
            assertNull(store.newest())
            assertTrue(groupLinks.joins == 0)
        }

    private class FakeGroupLinks : GroupJoinRepository {
        val updates = MutableSharedFlow<ZMGroupJoinUpdate>()
        var inspection = ZMGroupLinkInspection(ZMGroupLinkInspectStatus.OK, nameHint = "Hiking Crew", linkId = LINK_ID)
        var joinResult = requested()
        var status: List<ZMGroupJoinUpdate> = emptyList()
        var joins = 0
        val cancelled = mutableListOf<String>()

        override val joinUpdates = updates

        override suspend fun inspect(link: String) = Result.success(inspection)

        override suspend fun join(link: String): Result<ZMGroupJoinResult> {
            joins++
            return joinResult
        }

        override suspend fun joinStatus() = Result.success(status)

        override suspend fun cancel(linkId: String): Result<Boolean> {
            cancelled += linkId
            return Result.success(true)
        }
    }

    private companion object {
        const val LINK_ID = "c0ffee"

        fun requested() = Result.success(ZMGroupJoinResult(ZMGroupJoinRequestStatus.REQUESTED, LINK_ID))
    }
}
