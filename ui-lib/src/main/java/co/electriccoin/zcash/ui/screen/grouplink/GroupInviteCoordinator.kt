// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.grouplink

import co.electriccoin.zcash.ui.screen.grouplink.model.GroupInviteIntake
import co.electriccoin.zcash.ui.screen.grouplink.model.PendingGroupInviteStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull

// The app lock needs no handling here: it covers the screen, so the preview is what shows on unlock.
class GroupInviteCoordinator(
    private val store: PendingGroupInviteStore,
    private val onboardingDone: Flow<Boolean>,
    private val identityReady: Flow<Boolean>,
    private val isEnabled: Boolean,
) {
    /** Null when the link is held and opens by itself once the app is ready. */
    suspend fun intake(raw: String): GroupInvitePreviewArgs? =
        when {
            // Nothing is stored, so the secret goes nowhere.
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

    // Only collected while a wallet exists; the caller owns that condition.
    fun invitesToOpen(): Flow<String> =
        combine(onboardingDone, identityReady, store.observeTokens()) { done, ready, tokens ->
            if (isEnabled && done && ready) tokens.firstOrNull() else null
        }.distinctUntilChanged()
            .filterNotNull()
}
