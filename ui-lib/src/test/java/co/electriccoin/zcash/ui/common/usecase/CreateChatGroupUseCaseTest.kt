// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.screen.chat.repository.ChatConversationsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import xyz.justzappit.zappmessaging.ZappMessagingSDK
import xyz.justzappit.zappmessaging.models.ConversationType
import xyz.justzappit.zappmessaging.models.ZMConversation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The chat list and room read the repository's cache, not the SDK's list, so a group the SDK
 * created is invisible to both until the cache is refreshed. Without the refresh the room opened
 * for the new group has no title and the list has no row once the user backs out.
 */
class CreateChatGroupUseCaseTest {
    private val sdk = mockk<ZappMessagingSDK>()
    private val repository = mockk<ChatConversationsRepository>(relaxed = true)
    private val useCase = CreateChatGroupUseCase(sdk, repository)

    @Test
    fun `a created group is pulled into the conversation cache`() =
        runTest {
            coEvery { sdk.createConversation(ConversationType.GROUP, PARTICIPANTS, "Trip") } returns
                ZMConversation(
                    id = "group-1",
                    type = ConversationType.GROUP,
                    participantIds = PARTICIPANTS,
                    displayName = "Trip",
                )

            val result = useCase("Trip", PARTICIPANTS)

            assertEquals("group-1", result.getOrThrow())
            coVerify(exactly = 1) { repository.refresh() }
        }

    @Test
    fun `a failed creation refreshes nothing`() =
        runTest {
            coEvery { sdk.createConversation(any(), any(), any()) } throws IllegalStateException("offline")

            val result = useCase("Trip", PARTICIPANTS)

            assertTrue(result.isFailure)
            coVerify(exactly = 0) { repository.refresh() }
        }

    private companion object {
        val PARTICIPANTS = listOf("a".repeat(64), "b".repeat(64))
    }
}
