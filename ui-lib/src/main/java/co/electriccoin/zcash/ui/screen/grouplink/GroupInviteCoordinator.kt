// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.grouplink

import co.electriccoin.zcash.ui.screen.grouplink.model.GroupInviteIntake
import co.electriccoin.zcash.ui.screen.grouplink.model.PendingGroupInviteStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull

/**
 * Decides when a tapped invite may open.
 *
 * A link can land at any point: before a wallet exists, halfway through onboarding, before the chat
 * identity is made, or behind the app lock. It waits in [store] until there is someone to join as,
 * then opens once. The lock needs no handling here: it covers the screen, so the preview is simply
 * what the person sees after unlocking.
 */
class GroupInviteCoordinator(
    private val store: PendingGroupInviteStore,
    /** True once the welcome gate and onboarding are both behind the person. */
    private val onboardingDone: Flow<Boolean>,
    /** True while a chat identity exists to join as. */
    private val identityReady: Flow<Boolean>,
    private val isEnabled: Boolean,
) {
    /**
     * Takes a tapped link. Returns a screen to open right away, or null when the link is held and
     * will open by itself once the app is ready.
     */
    suspend fun intake(raw: String): GroupInvitePreviewArgs? =
        when {
            // Nothing is stored: the person hears it is coming soon, and the secret goes nowhere.
            !isEnabled -> {
                GroupInvitePreviewArgs(comingSoon = true)
            }

            else -> {
                when (store.put(raw)) {
                    is GroupInviteIntake.Accepted -> null
                    GroupInviteIntake.Refused -> GroupInvitePreviewArgs(token = null)
                }
            }
        }

    /**
     * The token to open next, each time it changes, once there is someone to join as. The wallet
     * itself is the caller's condition: this runs only while one is ready.
     */
    fun invitesToOpen(): Flow<String> =
        combine(onboardingDone, identityReady, store.observeTokens()) { done, ready, tokens ->
            if (isEnabled && done && ready) tokens.firstOrNull() else null
        }.distinctUntilChanged()
            .filterNotNull()
}
