// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.grouplink

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.CopyFeedback
import co.electriccoin.zcash.ui.common.usecase.CopyToClipboardUseCase
import co.electriccoin.zcash.ui.common.usecase.ShareGroupLinkUseCase
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.zapp.ZappButtonVariant
import co.electriccoin.zcash.ui.design.component.zapp.ZappConfirmationState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.chat.model.ChatConversation
import co.electriccoin.zcash.ui.screen.chat.repository.ChatContactsRepository
import co.electriccoin.zcash.ui.screen.chat.repository.ChatConversationsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.justzappit.zappmessaging.models.ZMGroupJoinApprovalRequest
import xyz.justzappit.zappmessaging.models.ZMGroupLinkApproval
import xyz.justzappit.zappmessaging.models.ZMGroupLinkInfo
import xyz.justzappit.zappmessaging.models.ZMGroupLinkOptions
import xyz.justzappit.zappmessaging.models.ZMGroupLinkState

@Suppress("TooManyFunctions")
class GroupLinkVM(
    private val args: GroupLinkArgs,
    private val groupLinks: GroupLinkRepository,
    private val conversations: ChatConversationsRepository,
    private val contacts: ChatContactsRepository,
    private val copyToClipboard: CopyToClipboardUseCase,
    private val shareGroupLink: ShareGroupLinkUseCase,
    private val navigationRouter: NavigationRouter,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val info = MutableStateFlow<ZMGroupLinkInfo?>(null)
    private val ui = MutableStateFlow(GroupLinkUi())
    private val requests = MutableStateFlow<List<ZMGroupJoinApprovalRequest>>(emptyList())
    private val copyFeedback = CopyFeedback(viewModelScope)

    private val waiting: Flow<List<PendingRequest>> =
        combine(requests, contacts.contacts) { pending, saved ->
            pending.map { request ->
                PendingRequest(request, saved.firstOrNull { it.publicKey == request.joinerKey }?.name)
            }
        }

    val state: StateFlow<GroupLinkState> =
        combine(
            info,
            ui,
            copyFeedback.copiedValue,
            conversations.conversation(args.conversationId),
            waiting,
        ) { link, ui, copied, conversation, pending ->
            createState(link, ui, copied, conversation, pending)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue = createState(null, GroupLinkUi(), null, null, emptyList()),
        )

    init {
        load()
        viewModelScope.launch {
            merge(groupLinks.joinRequests.map { it.conversationId }, groupLinks.withdrawnRequests)
                .filter { it == args.conversationId }
                .collect { loadRequests() }
        }
    }

    private fun load() {
        ui.update { it.copy(failed = false) }
        refresh()
    }

    private fun refresh() {
        viewModelScope.launch {
            groupLinks
                .get(args.conversationId)
                .onSuccess { info.value = it }
                .onFailure { ui.update { current -> current.copy(failed = true) } }
        }
        loadRequests()
    }

    private fun loadRequests() {
        viewModelScope.launch {
            groupLinks.requests(args.conversationId).onSuccess { requests.value = it }
        }
    }

    private fun run(block: suspend () -> Result<ZMGroupLinkInfo>) {
        if (ui.value.isBusy) return
        ui.update { it.copy(isBusy = true, failed = false, isFull = false, picker = null, isConfirmingReset = false) }
        viewModelScope.launch {
            block()
                .onSuccess {
                    info.value = it
                    ui.update { current -> current.copy(isBusy = false) }
                }.onFailure {
                    ui.update { current -> current.copy(isBusy = false, failed = true) }
                }
        }
    }

    private fun onTurnOnClick() = run { groupLinks.enable(args.conversationId, ZMGroupLinkOptions()) }

    private fun onTurnOffClick() = run { groupLinks.disable(args.conversationId) }

    private fun onResetClick() = ui.update { it.copy(isConfirmingReset = true) }

    private fun onResetConfirmed() = run { groupLinks.reset(args.conversationId) }

    private fun onExpiryPicked(days: Long?) =
        run {
            groupLinks.update(
                args.conversationId,
                if (days == null) {
                    ZMGroupLinkOptions(clearExpiry = true)
                } else {
                    ZMGroupLinkOptions(expiresAt = now() + days * DAY_MS)
                },
            )
        }

    private fun onLimitPicked(maxJoins: Int?) =
        run {
            groupLinks.update(
                args.conversationId,
                if (maxJoins == null) {
                    ZMGroupLinkOptions(clearMaxJoins = true)
                } else {
                    ZMGroupLinkOptions(maxJoins = maxJoins)
                },
            )
        }

    private fun onNameToggle(includeName: Boolean) =
        run { groupLinks.update(args.conversationId, ZMGroupLinkOptions(includeName = includeName)) }

    private fun onApprovalToggle(approval: ZMGroupLinkApproval) =
        run { groupLinks.update(args.conversationId, ZMGroupLinkOptions(approval = approval)) }

    // Approving runs the group's checks again, so it can come back full; the SDK clears the request either way.
    private fun onApproveClick(key: String) {
        answer(call = { groupLinks.approve(args.conversationId, key) }, fullMessage = true)
    }

    private fun onDeclineClick(key: String) {
        answer(call = { groupLinks.decline(args.conversationId, key).map { true } }, fullMessage = false)
    }

    private fun answer(
        call: suspend () -> Result<Boolean>,
        fullMessage: Boolean,
    ) {
        if (ui.value.isBusy) return
        ui.update { it.copy(isBusy = true, failed = false, isFull = false) }
        viewModelScope.launch {
            call()
                .onSuccess { admitted ->
                    ui.update { current ->
                        current.copy(isBusy = false, isFull = fullMessage && !admitted)
                    }
                }.onFailure { ui.update { current -> current.copy(isBusy = false, failed = true) } }
            refresh()
        }
    }

    private fun onCopyClick(link: String) {
        copyToClipboard(link, isSensitive = true)
        copyFeedback.mark(link)
    }

    private fun onShareClick(link: String) {
        if (!shareGroupLink(link)) ui.update { it.copy(failed = true) }
    }

    private fun onPickerOpen(kind: GroupLinkPickerKind) = ui.update { it.copy(picker = kind) }

    private fun dismissPicker() = ui.update { it.copy(picker = null) }

    private fun dismissConfirmation() = ui.update { it.copy(isConfirmingReset = false) }

    private fun onBack() = navigationRouter.back()

    private fun createState(
        link: ZMGroupLinkInfo?,
        ui: GroupLinkUi,
        copied: String?,
        conversation: ChatConversation?,
        pending: List<PendingRequest>,
    ): GroupLinkState {
        // Only the creator can admit anyone.
        if (conversation != null && !conversation.isOwner) {
            return GroupLinkState(
                isLoading = false,
                card = null,
                warning = null,
                historyNote = null,
                notice = stringRes(R.string.group_link_owner_only),
                error = null,
                actions = emptyList(),
                requests = emptyList(),
                rows = emptyList(),
                toggles = emptyList(),
                picker = null,
                confirmation = null,
                onBack = ::onBack,
            )
        }

        val isActive = link?.state == ZMGroupLinkState.ACTIVE
        return GroupLinkState(
            isLoading = link == null && !ui.failed,
            card =
                link?.link?.takeIf { isActive }?.let { value ->
                    GroupLinkCardState(link = value, isCopied = copied == value, onCopyClick = { onCopyClick(value) })
                },
            warning = stringRes(R.string.group_link_warning).takeIf { isActive },
            historyNote = stringRes(R.string.group_link_history_note).takeIf { link != null },
            notice = notice(link),
            error = error(ui),
            actions = actions(link, ui),
            requests = pending.map { request(it, !ui.isBusy) },
            rows = if (isActive) rows(link) else emptyList(),
            toggles = if (isActive) toggles(link) else emptyList(),
            picker = ui.picker?.let { picker(it, link) },
            confirmation = if (ui.isConfirmingReset) resetConfirmation() else null,
            onBack = ::onBack,
        )
    }

    private fun error(ui: GroupLinkUi): StringResource? =
        when {
            ui.failed -> stringRes(R.string.group_link_action_failed)
            ui.isFull -> stringRes(R.string.group_invite_full)
            else -> null
        }

    private fun request(
        pending: PendingRequest,
        isEnabled: Boolean,
    ): GroupLinkRequestState {
        val key = pending.request.joinerKey
        return GroupLinkRequestState(
            key = key,
            name = pending.request.joinerName,
            subtitle = stringRes(R.string.group_link_request_subtitle),
            contactHint = pending.contactName?.let { stringRes(R.string.group_link_request_contact_fmt, it) },
            tag = stringRes(R.string.group_link_request_removed_before).takeIf { pending.request.previouslyRemoved },
            isEnabled = isEnabled,
            onApprove = { onApproveClick(key) },
            onDecline = { onDeclineClick(key) },
        )
    }

    private fun notice(link: ZMGroupLinkInfo?): StringResource? =
        when {
            link?.state == ZMGroupLinkState.OFF -> stringRes(R.string.group_link_off_note)
            link?.approvalReason == RATE_REASON -> stringRes(R.string.group_link_paused)
            else -> null
        }

    private fun actions(
        link: ZMGroupLinkInfo?,
        ui: GroupLinkUi,
    ): List<GroupLinkActionState> {
        if (link == null) {
            return if (ui.failed) {
                listOf(action(R.string.group_link_retry, ZappButtonVariant.Primary, !ui.isBusy, ::load))
            } else {
                emptyList()
            }
        }
        val enabled = !ui.isBusy
        val value = link.link
        return if (link.state == ZMGroupLinkState.ACTIVE && value != null) {
            listOf(
                action(R.string.group_link_copy, ZappButtonVariant.Primary, enabled) { onCopyClick(value) },
                action(R.string.group_link_share, ZappButtonVariant.Secondary, enabled) { onShareClick(value) },
                action(R.string.group_link_reset, ZappButtonVariant.Ghost, enabled, ::onResetClick),
                action(R.string.group_link_turn_off, ZappButtonVariant.Ghost, enabled, ::onTurnOffClick),
            )
        } else {
            listOf(action(R.string.group_link_turn_on, ZappButtonVariant.Primary, enabled, ::onTurnOnClick))
        }
    }

    private fun action(
        text: Int,
        variant: ZappButtonVariant,
        isEnabled: Boolean,
        onClick: () -> Unit,
    ) = GroupLinkActionState(text = stringRes(text), variant = variant, isEnabled = isEnabled, onClick = onClick)

    private fun rows(link: ZMGroupLinkInfo?): List<GroupLinkRowState> =
        listOf(
            GroupLinkRowState(
                title = stringRes(R.string.group_link_expiry_label),
                value = expiryValue(link?.expiresAt),
                onClick = { onPickerOpen(GroupLinkPickerKind.EXPIRY) },
            ),
            GroupLinkRowState(
                title = stringRes(R.string.group_link_limit_label),
                value = GroupLinkCopy.limit(link?.maxJoins),
                onClick = { onPickerOpen(GroupLinkPickerKind.LIMIT) },
            ),
        )

    private fun toggles(link: ZMGroupLinkInfo?): List<GroupLinkToggleState> {
        val includeName = link?.includeName != false
        val approves = link?.approval == ZMGroupLinkApproval.OWNER
        return listOf(
            GroupLinkToggleState(
                title = stringRes(R.string.group_link_name_toggle),
                subtitle = null,
                isChecked = includeName,
                onClick = { onNameToggle(!includeName) },
            ),
            GroupLinkToggleState(
                title = stringRes(R.string.group_link_approval_toggle),
                subtitle = stringRes(R.string.group_link_approval_help),
                isChecked = approves,
                onClick = {
                    onApprovalToggle(if (approves) ZMGroupLinkApproval.AUTO else ZMGroupLinkApproval.OWNER)
                },
            ),
        )
    }

    private fun picker(
        kind: GroupLinkPickerKind,
        link: ZMGroupLinkInfo?,
    ): GroupLinkPickerState =
        when (kind) {
            GroupLinkPickerKind.EXPIRY -> {
                val chosen = pickedExpiry(link?.expiresAt)
                GroupLinkPickerState(
                    title = stringRes(R.string.group_link_expiry_label),
                    options =
                        EXPIRY_CHOICES.map { days ->
                            GroupLinkPickerOption(GroupLinkCopy.expiry(days), days == chosen) { onExpiryPicked(days) }
                        },
                    onDismiss = ::dismissPicker,
                )
            }

            GroupLinkPickerKind.LIMIT -> {
                GroupLinkPickerState(
                    title = stringRes(R.string.group_link_limit_label),
                    options =
                        LIMIT_CHOICES.map { max ->
                            val label = GroupLinkCopy.limit(max)
                            GroupLinkPickerOption(label, max == link?.maxJoins) { onLimitPicked(max) }
                        },
                    onDismiss = ::dismissPicker,
                )
            }
        }

    private fun resetConfirmation() =
        ZappConfirmationState(
            title = stringRes(R.string.group_link_reset_title),
            message = stringRes(R.string.group_link_reset_body),
            primaryButton = ButtonState(stringRes(R.string.group_link_reset_confirm), onClick = ::onResetConfirmed),
            secondaryButton = ButtonState(stringRes(R.string.group_link_cancel), onClick = ::dismissConfirmation),
            isDestructive = true,
            onBack = ::dismissConfirmation,
        )

    private fun expiryValue(expiresAt: Long?): StringResource {
        val remaining = expiresAt?.minus(now()) ?: return GroupLinkCopy.expiry(null)
        return if (remaining <= 0) {
            stringRes(R.string.group_link_expired)
        } else {
            GroupLinkCopy.expiry((remaining + DAY_MS - 1) / DAY_MS)
        }
    }

    // The row shows the days actually left; only the picker's tick snaps to the preset.
    private fun pickedExpiry(expiresAt: Long?): Long? {
        val remaining = expiresAt?.minus(now()) ?: return null
        return EXPIRY_CHOICES.filterNotNull().firstOrNull { remaining in 1..it * DAY_MS } ?: NO_PRESET
    }

    override fun onCleared() {
        super.onCleared()
        copyFeedback.cancel()
    }

    private data class PendingRequest(
        val request: ZMGroupJoinApprovalRequest,
        val contactName: String?,
    )

    private data class GroupLinkUi(
        val isBusy: Boolean = false,
        val failed: Boolean = false,
        val isFull: Boolean = false,
        val picker: GroupLinkPickerKind? = null,
        val isConfirmingReset: Boolean = false,
    )

    private enum class GroupLinkPickerKind { EXPIRY, LIMIT }

    private companion object {
        const val DAY_MS = 86_400_000L
        const val NO_PRESET = -1L
        const val RATE_REASON = "rate"
        val EXPIRY_CHOICES = listOf<Long?>(null, 1L, 7L, 30L)
        val LIMIT_CHOICES = listOf<Int?>(null, 10, 25, 50, 100)
    }
}
