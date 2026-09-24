// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.grouplink

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.zapp.ZappButtonVariant
import co.electriccoin.zcash.ui.design.component.zapp.ZappConfirmationState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes

data class GroupLinkState(
    val isLoading: Boolean,
    val card: GroupLinkCardState?,
    val warning: StringResource?,
    val historyNote: StringResource?,
    val notice: StringResource?,
    val error: StringResource?,
    val actions: List<GroupLinkActionState>,
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
    /** Chosen by the joiner. Nothing has verified it. */
    val name: String,
    val subtitle: StringResource,
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

internal object GroupLinkCopy {
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
