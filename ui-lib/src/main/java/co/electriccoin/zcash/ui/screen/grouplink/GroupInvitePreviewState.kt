// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.grouplink

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.grouplink.model.GroupInviteFailure
import co.electriccoin.zcash.ui.screen.grouplink.model.GroupInvitePhase

data class GroupInvitePreviewState(
    /** Null while the link is being read. */
    val title: StringResource?,
    val body: StringResource? = null,
    /** A problem with the last action, shown under the body. */
    val note: StringResource? = null,
    val primary: ButtonState? = null,
    val secondary: ButtonState? = null,
    val onBack: () -> Unit,
)

/** Everything the screen can say, one phase at a time. Kept free of Android so it can be tested. */
internal object GroupInvitePreviewCopy {
    fun title(phase: GroupInvitePhase): StringResource? =
        when (phase) {
            GroupInvitePhase.Reading, GroupInvitePhase.Dismissed -> {
                null
            }

            is GroupInvitePhase.Preview -> {
                named(phase.nameHint, R.string.group_invite_title_named, R.string.group_invite_title)
            }

            is GroupInvitePhase.Requesting -> {
                named(phase.nameHint, R.string.group_invite_title_named, R.string.group_invite_title)
            }

            is GroupInvitePhase.Waiting -> {
                stringRes(R.string.group_invite_waiting_title)
            }

            is GroupInvitePhase.Joined -> {
                if (phase.alreadyMember) {
                    stringRes(R.string.group_invite_already_member)
                } else {
                    named(phase.nameHint, R.string.group_invite_joined_named, R.string.group_invite_joined)
                }
            }

            is GroupInvitePhase.Failed -> {
                stringRes(failure(phase.reason))
            }
        }

    fun body(phase: GroupInvitePhase): StringResource? =
        when (phase) {
            is GroupInvitePhase.Preview, is GroupInvitePhase.Requesting -> {
                stringRes(R.string.group_invite_body)
            }

            is GroupInvitePhase.Waiting -> {
                stringRes(
                    if (phase.withOwner) R.string.group_invite_waiting_owner else R.string.group_invite_waiting_body,
                )
            }

            else -> {
                null
            }
        }

    fun failure(reason: GroupInviteFailure): Int =
        when (reason) {
            GroupInviteFailure.UNREADABLE -> R.string.group_invite_unreadable
            GroupInviteFailure.EXPIRED -> R.string.group_invite_expired
            GroupInviteFailure.NEEDS_UPDATE -> R.string.group_invite_update
            GroupInviteFailure.INACTIVE -> R.string.group_invite_inactive
            GroupInviteFailure.FULL -> R.string.group_invite_full
            GroupInviteFailure.DECLINED -> R.string.group_invite_declined
            GroupInviteFailure.COMING_SOON -> R.string.group_invite_coming_soon
        }

    private fun named(
        name: String?,
        withName: Int,
        withoutName: Int,
    ): StringResource = if (name.isNullOrBlank()) stringRes(withoutName) else stringRes(withName, name)
}
