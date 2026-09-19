// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.grouplink

import co.electriccoin.zcash.ui.screen.grouplink.model.GroupInviteLinksTest.Companion.LINK
import co.electriccoin.zcash.ui.screen.grouplink.model.InMemoryPreferenceProvider
import co.electriccoin.zcash.ui.screen.grouplink.model.PendingGroupInviteStore
import co.electriccoin.zcash.ui.screen.grouplink.model.pendingInviteStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class GroupInviteCoordinatorTest {
    private val preferences = InMemoryPreferenceProvider()
    private val store = pendingInviteStore(preferences)
    private val onboardingDone = MutableStateFlow(false)
    private val identityReady = MutableStateFlow(false)

    private fun coordinator(enabled: Boolean = true) =
        GroupInviteCoordinator(store, onboardingDone, identityReady, enabled)

    @Test
    fun `with the flag off a link says coming soon and nothing is stored`() =
        runTest {
            assertEquals(GroupInvitePreviewArgs(comingSoon = true), coordinator(enabled = false).intake(LINK))
            assertNull(preferences.raw(PendingGroupInviteStore.PREF_KEY))
        }

    @Test
    fun `a link it cannot hold opens the explanation right away`() =
        runTest {
            val oversized = "https://join.justzappit.xyz/g/v1#" + "A".repeat(2000)
            assertEquals(GroupInvitePreviewArgs(token = null), coordinator().intake(oversized))
        }

    @Test
    fun `a good link is held, not opened, at intake`() =
        runTest {
            assertNull(coordinator().intake(LINK))
            assertEquals(LINK, store.link(store.newest()!!))
        }

    @Test
    fun `a held link opens only once onboarding is done and there is an identity`() =
        runTest {
            val coordinator = coordinator()
            val opened = mutableListOf<String>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                coordinator.invitesToOpen().collect { opened += it }
            }

            coordinator.intake(LINK)
            advanceUntilIdle()
            assertEquals(emptyList(), opened, "tapped before install or during onboarding")

            onboardingDone.value = true
            advanceUntilIdle()
            assertEquals(emptyList(), opened, "no chat identity yet")

            identityReady.value = true
            advanceUntilIdle()
            val token = store.newest()!!
            assertEquals(listOf(token), opened)

            // Nothing more changes, so it does not open twice.
            onboardingDone.value = true
            advanceUntilIdle()
            assertEquals(listOf(token), opened)
        }

    @Test
    fun `a second link while the first is open opens too`() =
        runTest {
            onboardingDone.value = true
            identityReady.value = true
            val coordinator = coordinator()
            val opened = mutableListOf<String>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                coordinator.invitesToOpen().collect { opened += it }
            }

            coordinator.intake(LINK)
            advanceUntilIdle()
            coordinator.intake(LINK.dropLast(1) + "Z")
            advanceUntilIdle()
            assertEquals(2, opened.size)
            assertEquals(store.newest(), opened.last())
        }

    @Test
    fun `nothing opens with the flag off even if something was stored earlier`() =
        runTest {
            coordinator().intake(LINK)
            onboardingDone.value = true
            identityReady.value = true
            val opened = mutableListOf<String>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                coordinator(enabled = false).invitesToOpen().collect { opened += it }
            }
            advanceUntilIdle()
            assertEquals(emptyList(), opened)
        }
}
