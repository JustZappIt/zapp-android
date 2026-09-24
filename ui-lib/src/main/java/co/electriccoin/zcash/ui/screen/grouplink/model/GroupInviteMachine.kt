// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.grouplink.model

import xyz.justzappit.zappmessaging.models.ZMGroupJoinRequestStatus
import xyz.justzappit.zappmessaging.models.ZMGroupJoinResult
import xyz.justzappit.zappmessaging.models.ZMGroupJoinStatus
import xyz.justzappit.zappmessaging.models.ZMGroupJoinUpdate
import xyz.justzappit.zappmessaging.models.ZMGroupLinkInspectStatus
import xyz.justzappit.zappmessaging.models.ZMGroupLinkInspection

enum class GroupInviteFailure {
    UNREADABLE,
    EXPIRED,
    NEEDS_UPDATE,
    INACTIVE,
    FULL,
    DECLINED,
    COMING_SOON,
}

/** Never stored: after process death it is derived again from the pending store and the SDK. */
sealed interface GroupInvitePhase {
    data object Reading : GroupInvitePhase

    data class Preview(
        val nameHint: String?,
        val linkId: String?,
        val sendFailed: Boolean = false,
    ) : GroupInvitePhase

    data class Requesting(
        val nameHint: String?,
        val linkId: String?,
    ) : GroupInvitePhase

    data class Waiting(
        val nameHint: String?,
        val linkId: String,
        val withOwner: Boolean,
    ) : GroupInvitePhase

    data class Joined(
        val nameHint: String?,
        val conversationId: String?,
        val alreadyMember: Boolean,
    ) : GroupInvitePhase

    data class Failed(
        val reason: GroupInviteFailure,
    ) : GroupInvitePhase

    data object Dismissed : GroupInvitePhase
}

sealed interface GroupInviteEvent {
    data class Inspected(
        val inspection: ZMGroupLinkInspection,
    ) : GroupInviteEvent

    data object Refused : GroupInviteEvent

    data object FlagOff : GroupInviteEvent

    data object Missing : GroupInviteEvent

    data object JoinTapped : GroupInviteEvent

    data object NotNowTapped : GroupInviteEvent

    data class Answered(
        val result: ZMGroupJoinResult,
    ) : GroupInviteEvent

    data object SendFailed : GroupInviteEvent

    data class Updated(
        val update: ZMGroupJoinUpdate,
    ) : GroupInviteEvent

    data object CancelTapped : GroupInviteEvent
}

data class GroupInviteStep(
    val phase: GroupInvitePhase,
    val dropLink: Boolean = false,
)

object GroupInviteMachine {
    fun reduce(
        phase: GroupInvitePhase,
        event: GroupInviteEvent,
    ): GroupInviteStep =
        when (phase) {
            GroupInvitePhase.Reading -> fromReading(event)

            is GroupInvitePhase.Preview -> fromPreview(phase, event)

            is GroupInvitePhase.Requesting -> fromRequesting(phase, event)

            is GroupInvitePhase.Waiting -> fromWaiting(phase, event)

            is GroupInvitePhase.Joined,
            is GroupInvitePhase.Failed,
            GroupInvitePhase.Dismissed -> GroupInviteStep(phase)
        }

    private fun fromReading(event: GroupInviteEvent): GroupInviteStep =
        when (event) {
            is GroupInviteEvent.Inspected -> {
                when (event.inspection.status) {
                    ZMGroupLinkInspectStatus.OK -> {
                        GroupInviteStep(GroupInvitePhase.Preview(event.inspection.nameHint, event.inspection.linkId))
                    }

                    ZMGroupLinkInspectStatus.EXPIRED -> {
                        fail(GroupInviteFailure.EXPIRED)
                    }

                    ZMGroupLinkInspectStatus.MALFORMED -> {
                        fail(GroupInviteFailure.UNREADABLE)
                    }

                    ZMGroupLinkInspectStatus.UNSUPPORTED_VERSION,
                    ZMGroupLinkInspectStatus.NEWER_FORMAT -> {
                        fail(GroupInviteFailure.NEEDS_UPDATE)
                    }
                }
            }

            GroupInviteEvent.Refused -> {
                fail(GroupInviteFailure.UNREADABLE)
            }

            GroupInviteEvent.FlagOff -> {
                fail(GroupInviteFailure.COMING_SOON)
            }

            GroupInviteEvent.Missing -> {
                GroupInviteStep(GroupInvitePhase.Dismissed)
            }

            GroupInviteEvent.NotNowTapped -> {
                GroupInviteStep(GroupInvitePhase.Dismissed, dropLink = true)
            }

            else -> {
                GroupInviteStep(GroupInvitePhase.Reading)
            }
        }

    private fun fromPreview(
        phase: GroupInvitePhase.Preview,
        event: GroupInviteEvent,
    ): GroupInviteStep =
        when (event) {
            GroupInviteEvent.JoinTapped -> {
                GroupInviteStep(GroupInvitePhase.Requesting(phase.nameHint, phase.linkId))
            }

            GroupInviteEvent.NotNowTapped -> {
                GroupInviteStep(GroupInvitePhase.Dismissed, dropLink = true)
            }

            GroupInviteEvent.Missing -> {
                GroupInviteStep(GroupInvitePhase.Dismissed)
            }

            // Tapped again while a request from an earlier tap is still out, or after it landed.
            is GroupInviteEvent.Updated -> {
                when {
                    event.update.linkId != phase.linkId -> {
                        GroupInviteStep(phase)
                    }

                    event.update.status.isWaiting -> {
                        applyUpdate(phase.nameHint, event.update)
                    }

                    event.update.status == ZMGroupJoinStatus.JOINED -> {
                        GroupInviteStep(
                            GroupInvitePhase.Joined(
                                event.update.nameHint ?: phase.nameHint,
                                event.update.conversationId,
                                alreadyMember = true,
                            ),
                            dropLink = true,
                        )
                    }

                    // An earlier request through this link ended. This tap is a new try.
                    else -> {
                        GroupInviteStep(phase)
                    }
                }
            }

            else -> {
                GroupInviteStep(phase)
            }
        }

    private fun fromRequesting(
        phase: GroupInvitePhase.Requesting,
        event: GroupInviteEvent,
    ): GroupInviteStep =
        when (event) {
            is GroupInviteEvent.Answered -> {
                answered(phase, event.result)
            }

            GroupInviteEvent.SendFailed -> {
                GroupInviteStep(GroupInvitePhase.Preview(phase.nameHint, phase.linkId, sendFailed = true))
            }

            is GroupInviteEvent.Updated -> {
                if (event.update.linkId == phase.linkId) {
                    applyUpdate(phase.nameHint, event.update)
                } else {
                    GroupInviteStep(phase)
                }
            }

            else -> {
                GroupInviteStep(phase)
            }
        }

    private fun answered(
        phase: GroupInvitePhase.Requesting,
        result: ZMGroupJoinResult,
    ): GroupInviteStep =
        when (result.status) {
            ZMGroupJoinRequestStatus.REQUESTED,
            ZMGroupJoinRequestStatus.ALREADY_REQUESTED -> {
                val linkId = result.linkId ?: phase.linkId
                if (linkId == null) {
                    fail(GroupInviteFailure.UNREADABLE)
                } else {
                    GroupInviteStep(
                        GroupInvitePhase.Waiting(phase.nameHint, linkId, withOwner = false),
                        dropLink = true,
                    )
                }
            }

            ZMGroupJoinRequestStatus.ALREADY_MEMBER -> {
                GroupInviteStep(
                    GroupInvitePhase.Joined(phase.nameHint, result.conversationId, alreadyMember = true),
                    dropLink = true,
                )
            }

            ZMGroupJoinRequestStatus.EXPIRED -> {
                fail(GroupInviteFailure.EXPIRED)
            }

            ZMGroupJoinRequestStatus.MALFORMED -> {
                fail(GroupInviteFailure.UNREADABLE)
            }

            ZMGroupJoinRequestStatus.UNSUPPORTED_VERSION,
            ZMGroupJoinRequestStatus.NEWER_FORMAT -> {
                fail(GroupInviteFailure.NEEDS_UPDATE)
            }
        }

    private fun fromWaiting(
        phase: GroupInvitePhase.Waiting,
        event: GroupInviteEvent,
    ): GroupInviteStep =
        when (event) {
            is GroupInviteEvent.Updated -> {
                if (event.update.linkId == phase.linkId) {
                    applyUpdate(phase.nameHint, event.update)
                } else {
                    GroupInviteStep(phase)
                }
            }

            GroupInviteEvent.CancelTapped -> {
                GroupInviteStep(GroupInvitePhase.Dismissed, dropLink = true)
            }

            else -> {
                GroupInviteStep(phase)
            }
        }

    private fun applyUpdate(
        nameHint: String?,
        update: ZMGroupJoinUpdate,
    ): GroupInviteStep {
        val name = update.nameHint ?: nameHint
        val phase =
            when (update.status) {
                ZMGroupJoinStatus.WAITING -> GroupInvitePhase.Waiting(name, update.linkId, withOwner = false)

                ZMGroupJoinStatus.PENDING_APPROVAL -> GroupInvitePhase.Waiting(name, update.linkId, withOwner = true)

                ZMGroupJoinStatus.JOINED -> GroupInvitePhase.Joined(name, update.conversationId, alreadyMember = false)

                ZMGroupJoinStatus.INACTIVE -> GroupInvitePhase.Failed(GroupInviteFailure.INACTIVE)

                ZMGroupJoinStatus.EXPIRED -> GroupInvitePhase.Failed(GroupInviteFailure.EXPIRED)

                ZMGroupJoinStatus.FULL -> GroupInvitePhase.Failed(GroupInviteFailure.FULL)

                ZMGroupJoinStatus.DECLINED -> GroupInvitePhase.Failed(GroupInviteFailure.DECLINED)

                ZMGroupJoinStatus.CANCELLED -> GroupInvitePhase.Dismissed

                // A status a newer SDK knows and this app does not: keep waiting rather than guess.
                ZMGroupJoinStatus.UNKNOWN -> GroupInvitePhase.Waiting(name, update.linkId, withOwner = false)
            }
        return GroupInviteStep(phase, dropLink = true)
    }

    private fun fail(reason: GroupInviteFailure) = GroupInviteStep(GroupInvitePhase.Failed(reason), dropLink = true)
}
