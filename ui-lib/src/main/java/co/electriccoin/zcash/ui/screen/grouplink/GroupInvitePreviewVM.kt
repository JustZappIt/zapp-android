// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.grouplink

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.chat.ChatRoomArgs
import co.electriccoin.zcash.ui.screen.grouplink.model.GroupInviteEvent
import co.electriccoin.zcash.ui.screen.grouplink.model.GroupInviteMachine
import co.electriccoin.zcash.ui.screen.grouplink.model.GroupInvitePhase
import co.electriccoin.zcash.ui.screen.grouplink.model.PendingGroupInviteStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * One tapped invite, from reading the link to the answer.
 *
 * The rules live in [GroupInviteMachine]. This class only runs its effects: reading the stored
 * link, calling the SDK, deleting the link when a step says so, and navigating.
 */
class GroupInvitePreviewVM(
    private val args: GroupInvitePreviewArgs,
    private val store: PendingGroupInviteStore,
    private val groupLinks: GroupJoinRepository,
    private val navigationRouter: NavigationRouter,
) : ViewModel() {
    private val phase = MutableStateFlow<GroupInvitePhase>(GroupInvitePhase.Reading)

    val state: StateFlow<GroupInvitePreviewState> =
        phase
            .map(::toState)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT), toState(phase.value))

    init {
        viewModelScope.launch {
            groupLinks.joinUpdates.collect { send(GroupInviteEvent.Updated(it)) }
        }
        viewModelScope.launch { read() }
    }

    private suspend fun read() {
        val token = args.token
        when {
            args.comingSoon -> {
                send(GroupInviteEvent.FlagOff)
            }

            token == null -> {
                send(GroupInviteEvent.Refused)
            }

            else -> {
                val link = store.link(token) ?: return send(GroupInviteEvent.Missing)
                // Reading is local, so a failure means the messaging service is not up yet, not
                // that the link is bad. Keep the link: it opens again on the next launch.
                val inspection =
                    groupLinks.inspect(link).getOrNull()
                        ?: delay(INSPECT_RETRY_MS).let { groupLinks.inspect(link).getOrNull() }
                        ?: return send(GroupInviteEvent.Missing)
                send(GroupInviteEvent.Inspected(inspection))
                refreshFromSdk(inspection.linkId)
            }
        }
    }

    /** A request an earlier tap already made, which the SDK knows about and this screen does not. */
    private suspend fun refreshFromSdk(linkId: String?) {
        if (linkId == null) return
        groupLinks
            .joinStatus()
            .getOrNull()
            ?.firstOrNull { it.linkId == linkId }
            ?.let { send(GroupInviteEvent.Updated(it)) }
    }

    private fun onJoin() {
        val token = args.token ?: return
        send(GroupInviteEvent.JoinTapped)
        viewModelScope.launch {
            val link = store.link(token) ?: return@launch send(GroupInviteEvent.Missing)
            groupLinks
                .join(link)
                .onSuccess { result ->
                    send(GroupInviteEvent.Answered(result))
                    refreshFromSdk(result.linkId)
                }.onFailure { send(GroupInviteEvent.SendFailed) }
        }
    }

    private fun onCancelRequest(linkId: String) {
        viewModelScope.launch {
            groupLinks.cancel(linkId)
            send(GroupInviteEvent.CancelTapped)
        }
    }

    private fun onOpenGroup(conversationId: String) {
        navigationRouter.replace(ChatRoomArgs(conversationId = conversationId))
    }

    /** Leaving a preview unanswered is a "not now". Leaving a waiting request keeps it waiting. */
    private fun onBack() {
        when (phase.value) {
            GroupInvitePhase.Reading, is GroupInvitePhase.Preview -> send(GroupInviteEvent.NotNowTapped)
            else -> navigationRouter.back()
        }
    }

    private fun send(event: GroupInviteEvent) {
        val step = GroupInviteMachine.reduce(phase.value, event)
        val leaving = step.phase == GroupInvitePhase.Dismissed && phase.value != GroupInvitePhase.Dismissed
        phase.value = step.phase
        val token = args.token
        if (step.dropLink && token != null) {
            // Delete first, then leave: leaving clears this scope, and the secret must not outlive it.
            viewModelScope.launch {
                store.remove(token)
                if (leaving) navigationRouter.back()
            }
        } else if (leaving) {
            navigationRouter.back()
        }
    }

    private fun toState(phase: GroupInvitePhase): GroupInvitePreviewState =
        GroupInvitePreviewState(
            title = GroupInvitePreviewCopy.title(phase),
            body = GroupInvitePreviewCopy.body(phase),
            note =
                if (phase is GroupInvitePhase.Preview && phase.sendFailed) {
                    stringRes(R.string.group_invite_send_failed)
                } else {
                    null
                },
            primary = primaryButton(phase),
            secondary = secondaryButton(phase),
            onBack = ::onBack,
        )

    private fun primaryButton(phase: GroupInvitePhase): ButtonState? =
        when (phase) {
            is GroupInvitePhase.Preview -> {
                ButtonState(stringRes(R.string.group_invite_join), onClick = ::onJoin)
            }

            is GroupInvitePhase.Requesting -> {
                ButtonState(stringRes(R.string.group_invite_join), isLoading = true)
            }

            is GroupInvitePhase.Joined -> {
                phase.conversationId?.let { id ->
                    ButtonState(stringRes(R.string.group_invite_open_group), onClick = { onOpenGroup(id) })
                }
            }

            else -> {
                null
            }
        }

    private fun secondaryButton(phase: GroupInvitePhase): ButtonState? =
        when (phase) {
            is GroupInvitePhase.Preview -> {
                ButtonState(stringRes(R.string.group_invite_not_now), onClick = { send(GroupInviteEvent.NotNowTapped) })
            }

            is GroupInvitePhase.Waiting -> {
                ButtonState(stringRes(R.string.group_invite_cancel), onClick = { onCancelRequest(phase.linkId) })
            }

            else -> {
                null
            }
        }

    private companion object {
        const val INSPECT_RETRY_MS = 1_500L
    }
}
