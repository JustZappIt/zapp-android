// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.grouplink

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.zapp.ZappButtonVariant
import co.electriccoin.zcash.ui.design.component.zapp.ZappConfirmationState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes

/**
 * The owner's view of one group's invite link.
 *
 * A null [card] means there is nothing to copy or share yet, either because the link is off or
 * because the group never had one. The link itself lives only here and in the clipboard, share and
 * QR actions: it is a bearer secret, so it never reaches navigation state or a log.
 */
data class GroupLinkState(
    val isLoading: Boolean,
    val card: GroupLinkCardState?,
    val warning: StringResource?,
    val historyNote: StringResource?,
    /** The link is off, approval switched itself on, or this person does not own the group. */
    val notice: StringResource?,
    val error: StringResource?,
    val actions: List<GroupLinkActionState>,
    /** People waiting for the owner to let them in, newest last. Empty unless approval is on. */
    val requests: List<GroupLinkRequestState>,
    val rows: List<GroupLinkRowState>,
    val toggles: List<GroupLinkToggleState>,
    val picker: GroupLinkPickerState?,
    val confirmation: ZappConfirmationState?,
    val onBack: () -> Unit,
)

data class GroupLinkCardState(
    val link: String,
    val isCopied: Boolean,
    val onCopyClick: () -> Unit,
)

data class GroupLinkActionState(
    val text: StringResource,
    val variant: ZappButtonVariant,
    val isEnabled: Boolean = true,
    val onClick: () -> Unit,
)

data class GroupLinkRequestState(
    val key: String,
    /** The name the person chose for themselves. Nothing has verified it. */
    val name: String,
    val subtitle: StringResource,
    /** The owner's own name for them, when they are already a contact. */
    val contactHint: StringResource?,
    val tag: StringResource?,
    val isEnabled: Boolean,
    val onApprove: () -> Unit,
    val onDecline: () -> Unit,
)

data class GroupLinkRowState(
    val title: StringResource,
    val value: StringResource,
    val onClick: () -> Unit,
)

data class GroupLinkToggleState(
    val title: StringResource,
    val subtitle: StringResource?,
    val isChecked: Boolean,
    val onClick: () -> Unit,
)

data class GroupLinkPickerState(
    val title: StringResource,
    val options: List<GroupLinkPickerOption>,
    val onDismiss: () -> Unit,
)

data class GroupLinkPickerOption(
    val title: StringResource,
    val isSelected: Boolean,
    val onClick: () -> Unit,
)

/** The labels the link's own settings carry. Kept free of Android so it can be tested. */
internal object GroupLinkCopy {
    /** [days] is the window the owner chose, not a date, because the link expires relative to now. */
    fun expiry(days: Long?): StringResource =
        when (days) {
            null -> stringRes(R.string.group_link_expiry_never)
            1L -> stringRes(R.string.group_link_expiry_1_day)
            else -> stringRes(R.string.group_link_expiry_days_fmt, days.toString())
        }

    fun limit(maxJoins: Int?): StringResource =
        if (maxJoins == null) {
            stringRes(R.string.group_link_limit_none)
        } else {
            stringRes(R.string.group_link_limit_joins_fmt, maxJoins.toString())
        }
}
