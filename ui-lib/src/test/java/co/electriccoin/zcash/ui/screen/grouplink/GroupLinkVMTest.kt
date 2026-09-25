// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.grouplink

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.usecase.CopyToClipboardUseCase
import co.electriccoin.zcash.ui.common.usecase.ShareGroupLinkUseCase
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.screen.chat.model.ChatContact
import co.electriccoin.zcash.ui.screen.chat.model.ChatConversation
import co.electriccoin.zcash.ui.screen.chat.model.ConversationType
import co.electriccoin.zcash.ui.screen.chat.repository.ChatContactsRepository
import co.electriccoin.zcash.ui.screen.chat.repository.ChatConversationsRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import xyz.justzappit.zappmessaging.models.ZMGroupJoinApprovalRequest
import xyz.justzappit.zappmessaging.models.ZMGroupLinkApproval
import xyz.justzappit.zappmessaging.models.ZMGroupLinkInfo
import xyz.justzappit.zappmessaging.models.ZMGroupLinkOptions
import xyz.justzappit.zappmessaging.models.ZMGroupLinkState
import xyz.justzappit.zappmessaging.models.ZMRemoveMemberResult
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class GroupLinkVMTest {
    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val groupLinks = FakeGroupLinkRepository()
    private val conversation = MutableStateFlow(groupConversation(isOwner = true))
    private val conversations =
        mockk<ChatConversationsRepository>(relaxed = true) {
            every { conversation(CONVERSATION_ID) } returns this@GroupLinkVMTest.conversation
        }
    private val savedContacts = MutableStateFlow(emptyList<ChatContact>())
    private val contacts =
        mockk<ChatContactsRepository>(relaxed = true) {
            every { contacts } returns savedContacts
        }
    private val copyToClipboard = mockk<CopyToClipboardUseCase>(relaxed = true)
    private val share = mockk<ShareGroupLinkUseCase>(relaxed = true)
    private val router = mockk<NavigationRouter>(relaxed = true)

    private suspend fun TestScope.open(): GroupLinkVM {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm =
            GroupLinkVM(
                args = GroupLinkArgs(CONVERSATION_ID),
                groupLinks = groupLinks,
                conversations = conversations,
                contacts = contacts,
                copyToClipboard = copyToClipboard,
                shareGroupLink = share,
                navigationRouter = router,
                now = { NOW },
            )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        advanceUntilIdle()
        return vm
    }

    @Test
    fun `an active link is shown with the warning and the history note`() =
        runTest {
            val vm = open()
            assertEquals(
                LINK,
                vm.state.value.card
                    ?.link
            )
            assertEquals(
                R.string.group_link_warning,
                vm.state.value.warning
                    .res()
            )
            assertEquals(
                R.string.group_link_history_note,
                vm.state.value.historyNote
                    .res()
            )
            assertEquals(
                listOf(
                    R.string.group_link_copy,
                    R.string.group_link_share,
                    R.string.group_link_reset,
                    R.string.group_link_turn_off,
                ),
                vm.state.value.actions
                    .map { it.text.res() },
            )
        }

    @Test
    fun `a group with no link offers to make one`() =
        runTest {
            groupLinks.info = ZMGroupLinkInfo(CONVERSATION_ID, ZMGroupLinkState.NONE)
            val vm = open()
            assertNull(vm.state.value.card, "there is nothing to copy yet")
            assertNull(vm.state.value.warning, "no link, so nobody can join with it")
            assertEquals(
                listOf(R.string.group_link_turn_on),
                vm.state.value.actions
                    .map { it.text.res() }
            )

            vm.state.value.actions
                .single()
                .onClick()
            advanceUntilIdle()
            assertEquals(1, groupLinks.enables)
            assertEquals(
                LINK,
                vm.state.value.card
                    ?.link
            )
        }

    @Test
    fun `turning the link off leaves nothing to copy and says so`() =
        runTest {
            val vm = open()
            vm.state.value.actions
                .last()
                .onClick()
            advanceUntilIdle()
            assertEquals(1, groupLinks.disables)
            assertNull(vm.state.value.card)
            assertNull(vm.state.value.warning)
            assertEquals(
                R.string.group_link_off_note,
                vm.state.value.notice
                    .res()
            )
            assertEquals(
                listOf(R.string.group_link_turn_on),
                vm.state.value.actions
                    .map { it.text.res() }
            )
        }

    @Test
    fun `reset asks first and only then replaces the link`() =
        runTest {
            val vm = open()
            vm.state.value.actions
                .first { it.text.res() == R.string.group_link_reset }
                .onClick()
            runCurrent()

            val confirmation = assertNotNull(vm.state.value.confirmation, "a reset is not undoable, so it is asked")
            assertEquals(0, groupLinks.resets)
            confirmation.primaryButton.onClick()
            advanceUntilIdle()
            assertEquals(1, groupLinks.resets)
            assertNull(vm.state.value.confirmation)
        }

    @Test
    fun `the expiry picker sets a window from now and the row reads it back`() =
        runTest {
            val vm = open()
            vm.state.value.rows
                .first { it.title.res() == R.string.group_link_expiry_label }
                .onClick()
            runCurrent()
            val picker = assertNotNull(vm.state.value.picker)
            assertEquals(
                R.string.group_link_expiry_never,
                picker.options
                    .first()
                    .title
                    .res()
            )
            assertTrue(picker.options.first().isSelected, "a link with no expiry sits on Never")

            picker.options[WEEK_OPTION].onClick()
            advanceUntilIdle()
            assertEquals(NOW + SEVEN_DAYS_MS, groupLinks.updates.single().expiresAt)
            val row =
                vm.state.value.rows
                    .first { it.title.res() == R.string.group_link_expiry_label }
            assertEquals(R.string.group_link_expiry_days_fmt, row.value.res())
            assertEquals(listOf("7"), (row.value as StringResource.ByResource).args)
        }

    @Test
    fun `the expiry row shows the days actually left and the picker the window chosen`() =
        runTest {
            groupLinks.info = groupLinks.info.copy(expiresAt = NOW + 2 * DAY_MS)
            val vm = open()
            val row =
                vm.state.value.rows
                    .first { it.title.res() == R.string.group_link_expiry_label }
            assertEquals(R.string.group_link_expiry_days_fmt, row.value.res())
            assertEquals(listOf("2"), (row.value as StringResource.ByResource).args)

            row.onClick()
            runCurrent()
            val picker = assertNotNull(vm.state.value.picker)
            assertEquals(listOf(WEEK_OPTION), picker.options.indices.filter { picker.options[it].isSelected })
        }

    @Test
    fun `an expiry already in the past reads as expired`() =
        runTest {
            groupLinks.info = groupLinks.info.copy(expiresAt = NOW - 1)
            val vm = open()
            val row =
                vm.state.value.rows
                    .first { it.title.res() == R.string.group_link_expiry_label }
            assertEquals(R.string.group_link_expired, row.value.res())
        }

    @Test
    fun `the join limit is set and cleared through one call each`() =
        runTest {
            val vm = open()
            vm.state.value.rows
                .first { it.title.res() == R.string.group_link_limit_label }
                .onClick()
            runCurrent()
            vm.state.value.picker!!
                .options
                .last()
                .onClick()
            advanceUntilIdle()
            assertEquals(HUNDRED, groupLinks.updates.last().maxJoins)

            vm.state.value.rows
                .first { it.title.res() == R.string.group_link_limit_label }
                .onClick()
            runCurrent()
            vm.state.value.picker!!
                .options
                .first()
                .onClick()
            advanceUntilIdle()
            assertTrue(groupLinks.updates.last().clearMaxJoins)
        }

    @Test
    fun `approval and the name in the link are one call each`() =
        runTest {
            val vm = open()
            vm.state.value.toggles
                .first { it.title.res() == R.string.group_link_approval_toggle }
                .onClick()
            advanceUntilIdle()
            assertEquals(ZMGroupLinkApproval.OWNER, groupLinks.updates.last().approval)

            vm.state.value.toggles
                .first { it.title.res() == R.string.group_link_name_toggle }
                .onClick()
            advanceUntilIdle()
            assertEquals(false, groupLinks.updates.last().includeName)
        }

    @Test
    fun `a link that switched itself to approval explains why`() =
        runTest {
            groupLinks.info =
                groupLinks.info.copy(approval = ZMGroupLinkApproval.OWNER, approvalReason = "rate")
            val vm = open()
            assertEquals(
                R.string.group_link_paused,
                vm.state.value.notice
                    .res()
            )
        }

    @Test
    fun `a call that fails keeps the last link on screen and says so`() =
        runTest {
            val vm = open()
            groupLinks.failNext = true
            vm.state.value.actions
                .first { it.text.res() == R.string.group_link_turn_off }
                .onClick()
            advanceUntilIdle()
            assertEquals(
                LINK,
                vm.state.value.card
                    ?.link,
                "the link is still on, as far as anyone knows"
            )
            assertEquals(
                R.string.group_link_action_failed,
                vm.state.value.error
                    .res()
            )
        }

    @Test
    fun `a first read that fails offers another try`() =
        runTest {
            groupLinks.failNext = true
            val vm = open()
            assertEquals(
                listOf(R.string.group_link_retry),
                vm.state.value.actions
                    .map { it.text.res() }
            )

            vm.state.value.actions
                .single()
                .onClick()
            advanceUntilIdle()
            assertEquals(
                LINK,
                vm.state.value.card
                    ?.link
            )
        }

    @Test
    fun `copying hands the clipboard a sensitive value and marks the card`() =
        runTest {
            val vm = open()
            vm.state.value.card!!
                .onCopyClick()
            runCurrent()
            verify { copyToClipboard(LINK, isSensitive = true) }
            assertTrue(
                vm.state.value.card!!
                    .isCopied
            )
        }

    @Test
    fun `sharing opens the sheet with the link alone`() =
        runTest {
            val vm = open()
            vm.state.value.actions
                .first { it.text.res() == R.string.group_link_share }
                .onClick()
            verify { share(LINK) }
        }

    @Test
    fun `someone who does not own the group is told whose link it is`() =
        runTest {
            conversation.value = groupConversation(isOwner = false)
            val vm = open()
            assertEquals(
                R.string.group_link_owner_only,
                vm.state.value.notice
                    .res()
            )
            assertTrue(
                vm.state.value.actions
                    .isEmpty(),
                "there is nothing here for a member to do"
            )
            assertNull(vm.state.value.card)
        }

    @Test
    fun `waiting requests carry the owner's own name for someone already saved`() =
        runTest {
            groupLinks.pending = listOf(request(previouslyRemoved = true))
            savedContacts.value = listOf(ChatContact(publicKey = JOINER_KEY, name = "Sam from work"))
            val vm = open()

            val waiting =
                vm.state.value.requests
                    .single()
            assertEquals("Sam", waiting.name, "the name shown is the one the joiner chose")
            assertEquals(R.string.group_link_request_subtitle, waiting.subtitle.res())
            assertEquals(R.string.group_link_request_contact_fmt, waiting.contactHint.res())
            assertEquals(listOf("Sam from work"), (waiting.contactHint as StringResource.ByResource).args)
            assertEquals(R.string.group_link_request_removed_before, waiting.tag.res())
        }

    @Test
    fun `approving admits the joiner and the request leaves the list`() =
        runTest {
            groupLinks.pending = listOf(request())
            val vm = open()

            vm.state.value.requests
                .single()
                .onApprove()
            advanceUntilIdle()
            assertEquals(listOf(JOINER_KEY), groupLinks.approved)
            assertTrue(
                vm.state.value.requests
                    .isEmpty()
            )
            assertNull(vm.state.value.error)
        }

    @Test
    fun `approving into a full group says so and still clears the request`() =
        runTest {
            groupLinks.pending = listOf(request())
            groupLinks.admits = false
            val vm = open()

            vm.state.value.requests
                .single()
                .onApprove()
            advanceUntilIdle()
            assertEquals(
                R.string.group_invite_full,
                vm.state.value.error
                    .res()
            )
            assertTrue(
                vm.state.value.requests
                    .isEmpty()
            )
        }

    @Test
    fun `declining answers the joiner and the request leaves the list`() =
        runTest {
            groupLinks.pending = listOf(request())
            val vm = open()

            vm.state.value.requests
                .single()
                .onDecline()
            advanceUntilIdle()
            assertEquals(listOf(JOINER_KEY), groupLinks.declined)
            assertTrue(
                vm.state.value.requests
                    .isEmpty()
            )
        }

    @Test
    fun `an approve that fails says so and the request stays`() =
        runTest {
            groupLinks.pending = listOf(request())
            groupLinks.failAnswers = true
            val vm = open()

            vm.state.value.requests
                .single()
                .onApprove()
            advanceUntilIdle()
            assertEquals(
                R.string.group_link_action_failed,
                vm.state.value.error
                    .res()
            )
            assertEquals(
                JOINER_KEY,
                vm.state.value.requests
                    .single()
                    .key
            )
        }

    @Test
    fun `a decline that fails says so and the request stays`() =
        runTest {
            groupLinks.pending = listOf(request())
            groupLinks.failAnswers = true
            val vm = open()

            vm.state.value.requests
                .single()
                .onDecline()
            advanceUntilIdle()
            assertEquals(
                R.string.group_link_action_failed,
                vm.state.value.error
                    .res()
            )
            assertEquals(
                JOINER_KEY,
                vm.state.value.requests
                    .single()
                    .key
            )
        }

    @Test
    fun `a request that arrives while the screen is open joins the list`() =
        runTest {
            val vm = open()
            assertTrue(
                vm.state.value.requests
                    .isEmpty()
            )

            groupLinks.pending = listOf(request())
            groupLinks.joinRequests.emit(request())
            advanceUntilIdle()
            assertEquals(
                JOINER_KEY,
                vm.state.value.requests
                    .single()
                    .key
            )
        }

    @Test
    fun `a request taken back while the screen is open leaves the list`() =
        runTest {
            groupLinks.pending = listOf(request())
            val vm = open()
            assertEquals(1, vm.state.value.requests.size)

            groupLinks.pending = emptyList()
            groupLinks.withdrawnRequests.emit("another-group")
            advanceUntilIdle()
            assertEquals(1, vm.state.value.requests.size, "only this group's list reloads")

            groupLinks.withdrawnRequests.emit(CONVERSATION_ID)
            advanceUntilIdle()
            assertTrue(
                vm.state.value.requests
                    .isEmpty()
            )
        }

    private fun StringResource?.res() = (this as? StringResource.ByResource)?.resource

    private class FakeGroupLinkRepository : GroupLinkRepository {
        var info = ZMGroupLinkInfo(CONVERSATION_ID, ZMGroupLinkState.ACTIVE, link = LINK, linkId = LINK_ID)
        var failNext = false
        var enables = 0
        var disables = 0
        var resets = 0
        var admits = true
        var failAnswers = false
        var pending = emptyList<ZMGroupJoinApprovalRequest>()
        val updates = mutableListOf<ZMGroupLinkOptions>()
        val approved = mutableListOf<String>()
        val declined = mutableListOf<String>()

        override val joinRequests = MutableSharedFlow<ZMGroupJoinApprovalRequest>()

        override val withdrawnRequests = MutableSharedFlow<String>()

        private fun answer(change: () -> Unit): Result<ZMGroupLinkInfo> {
            if (failNext) {
                failNext = false
                return Result.failure(IllegalStateException("offline"))
            }
            change()
            return Result.success(info)
        }

        override suspend fun get(conversationId: String) = answer {}

        override suspend fun enable(
            conversationId: String,
            options: ZMGroupLinkOptions,
        ) = answer {
            enables++
            info = info.copy(state = ZMGroupLinkState.ACTIVE, link = LINK, linkId = LINK_ID)
        }

        override suspend fun update(
            conversationId: String,
            options: ZMGroupLinkOptions,
        ) = answer {
            updates += options
            info =
                info.copy(
                    expiresAt = if (options.clearExpiry) null else options.expiresAt ?: info.expiresAt,
                    maxJoins = if (options.clearMaxJoins) null else options.maxJoins ?: info.maxJoins,
                    includeName = options.includeName ?: info.includeName,
                    approval = options.approval ?: info.approval,
                )
        }

        override suspend fun reset(conversationId: String) =
            answer {
                resets++
                info = info.copy(link = "$LINK-2")
            }

        override suspend fun disable(conversationId: String) =
            answer {
                disables++
                info = info.copy(state = ZMGroupLinkState.OFF)
            }

        override suspend fun requests(conversationId: String) = Result.success(pending)

        override suspend fun approve(
            conversationId: String,
            joinerKey: String,
        ): Result<Boolean> {
            if (failAnswers) return Result.failure(IllegalStateException("offline"))
            approved += joinerKey
            pending = pending.filterNot { it.joinerKey == joinerKey }
            return Result.success(admits)
        }

        override suspend fun decline(
            conversationId: String,
            joinerKey: String,
        ): Result<Unit> {
            if (failAnswers) return Result.failure(IllegalStateException("offline"))
            declined += joinerKey
            pending = pending.filterNot { it.joinerKey == joinerKey }
            return Result.success(Unit)
        }

        override suspend fun removeMember(
            conversationId: String,
            publicKey: String,
            resetLink: Boolean,
        ) = Result.success(ZMRemoveMemberResult(emptyList(), 0))

        override suspend fun olderMemberCount(conversationId: String) = Result.success(0)
    }

    private companion object {
        const val CONVERSATION_ID = "g1"
        const val LINK = "https://join.justzappit.xyz/g/v1/AQE"
        const val LINK_ID = "c0ffee"
        const val NOW = 1_800_000_000_000L
        const val DAY_MS = 86_400_000L
        const val SEVEN_DAYS_MS = 7L * DAY_MS
        const val WEEK_OPTION = 2
        const val HUNDRED = 100
        const val JOINER_KEY = "beef"

        fun request(previouslyRemoved: Boolean = false) =
            ZMGroupJoinApprovalRequest(
                conversationId = CONVERSATION_ID,
                joinerKey = JOINER_KEY,
                joinerName = "Sam",
                previouslyRemoved = previouslyRemoved,
            )

        fun groupConversation(isOwner: Boolean) =
            ChatConversation(
                id = CONVERSATION_ID,
                type = ConversationType.GROUP,
                displayName = "Hiking Crew",
                isOwner = isOwner,
            )
    }
}
