// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.grouplink

import co.electriccoin.zcash.ui.screen.chat.common.runChatCallResult
import kotlinx.coroutines.flow.Flow
import xyz.justzappit.zappmessaging.ZappMessagingSDK
import xyz.justzappit.zappmessaging.models.ZMGroupJoinApprovalRequest
import xyz.justzappit.zappmessaging.models.ZMGroupJoinResult
import xyz.justzappit.zappmessaging.models.ZMGroupJoinUpdate
import xyz.justzappit.zappmessaging.models.ZMGroupLinkInfo
import xyz.justzappit.zappmessaging.models.ZMGroupLinkInspection
import xyz.justzappit.zappmessaging.models.ZMGroupLinkOptions
import xyz.justzappit.zappmessaging.models.ZMRemoveMemberResult

// Never log an argument or a result: links are bearer secrets, and keys and names identify people.

interface GroupJoinRepository {
    val joinUpdates: Flow<ZMGroupJoinUpdate>

    suspend fun inspect(link: String): Result<ZMGroupLinkInspection>

    suspend fun join(link: String): Result<ZMGroupJoinResult>

    suspend fun joinStatus(): Result<List<ZMGroupJoinUpdate>>

    suspend fun cancel(linkId: String): Result<Boolean>
}

interface GroupLinkRepository {
    val joinRequests: Flow<ZMGroupJoinApprovalRequest>

    suspend fun get(conversationId: String): Result<ZMGroupLinkInfo>

    suspend fun enable(
        conversationId: String,
        options: ZMGroupLinkOptions,
    ): Result<ZMGroupLinkInfo>

    suspend fun update(
        conversationId: String,
        options: ZMGroupLinkOptions,
    ): Result<ZMGroupLinkInfo>

    suspend fun reset(conversationId: String): Result<ZMGroupLinkInfo>

    suspend fun disable(conversationId: String): Result<ZMGroupLinkInfo>

    suspend fun requests(conversationId: String): Result<List<ZMGroupJoinApprovalRequest>>

    suspend fun approve(
        conversationId: String,
        joinerKey: String,
    ): Result<Boolean>

    suspend fun decline(
        conversationId: String,
        joinerKey: String,
    ): Result<Unit>

    suspend fun removeMember(
        conversationId: String,
        publicKey: String,
        resetLink: Boolean,
    ): Result<ZMRemoveMemberResult>

    suspend fun olderMemberCount(conversationId: String): Result<Int>
}

class GroupJoinRepositoryImpl(
    private val sdk: ZappMessagingSDK,
) : GroupJoinRepository {
    override val joinUpdates: Flow<ZMGroupJoinUpdate> = sdk.groupJoinUpdated

    override suspend fun inspect(link: String) = call("inspect failed") { sdk.inspectGroupLink(link) }

    override suspend fun join(link: String) = call("join failed") { sdk.joinGroupViaLink(link) }

    override suspend fun joinStatus() = call("join status failed") { sdk.groupJoinStatus() }

    override suspend fun cancel(linkId: String) = call("cancel failed") { sdk.cancelGroupJoin(linkId) }
}

class GroupLinkRepositoryImpl(
    private val sdk: ZappMessagingSDK,
) : GroupLinkRepository {
    override val joinRequests: Flow<ZMGroupJoinApprovalRequest> = sdk.groupJoinRequestReceived

    override suspend fun get(conversationId: String) = call("get failed") { sdk.getGroupLink(conversationId) }

    override suspend fun enable(
        conversationId: String,
        options: ZMGroupLinkOptions,
    ) = call("enable failed") { sdk.enableGroupLink(conversationId, options) }

    override suspend fun update(
        conversationId: String,
        options: ZMGroupLinkOptions,
    ) = call("update failed") { sdk.updateGroupLink(conversationId, options) }

    override suspend fun reset(conversationId: String) =
        call("reset failed") { sdk.resetGroupLink(conversationId) }

    override suspend fun disable(conversationId: String) =
        call("disable failed") { sdk.disableGroupLink(conversationId) }

    override suspend fun requests(conversationId: String) =
        call("requests failed") { sdk.groupJoinRequests(conversationId) }

    override suspend fun approve(
        conversationId: String,
        joinerKey: String,
    ) = call("approve failed") { sdk.approveGroupJoinRequest(conversationId, joinerKey) }

    override suspend fun decline(
        conversationId: String,
        joinerKey: String,
    ) = call("decline failed") { sdk.declineGroupJoinRequest(conversationId, joinerKey) }

    override suspend fun removeMember(
        conversationId: String,
        publicKey: String,
        resetLink: Boolean,
    ) = call("remove member failed") { sdk.removeMember(conversationId, publicKey, resetLink) }

    override suspend fun olderMemberCount(conversationId: String) =
        call("older member count failed") { sdk.olderMemberCount(conversationId) }
}

private inline fun <T> call(
    what: String,
    block: () -> T,
): Result<T> = runChatCallResult("GroupLinkRepository: $what", block)
