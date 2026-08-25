// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.provider.HasSeenHowToVoteKeystoneStorageProvider
import co.electriccoin.zcash.ui.common.provider.HasSeenHowToVoteStorageProvider
import co.electriccoin.zcash.ui.common.voting.VotingSettingsEntry

/**
 * Where the voting entry point lands: the explainer the first time this wallet votes, the polls
 * list every time after. Whether the explainer has been seen is tracked per wallet kind, because a
 * Keystone signs bundles on the device itself and so reads a different set of steps.
 *
 * Upstream keeps this decision inline in `MoreVM`. The fork surfaces voting from its own "You" tab
 * (`SettingsTabContent`) as well, and upstream's More screen is only reachable from a debug
 * gesture, so the decision lives here instead of being written twice.
 */
class NavigateToVotingUseCase(
    private val getSelectedWalletAccount: GetSelectedWalletAccountUseCase,
    private val hasSeenHowToVote: HasSeenHowToVoteStorageProvider,
    private val hasSeenHowToVoteKeystone: HasSeenHowToVoteKeystoneStorageProvider,
    private val votingSettingsEntry: VotingSettingsEntry,
) {
    val isEnabled: Boolean get() = votingSettingsEntry.isEnabled

    suspend operator fun invoke() {
        val isKeystone = getSelectedWalletAccount() is KeystoneAccount
        val hasSeenExplainer =
            if (isKeystone) hasSeenHowToVoteKeystone.get() else hasSeenHowToVote.get()

        if (hasSeenExplainer) {
            votingSettingsEntry.navigateToCoinholderPolling()
        } else {
            votingSettingsEntry.navigateToHowToVote()
        }
    }
}
