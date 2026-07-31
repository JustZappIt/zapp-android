// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.view

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Security
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.zapp.ZappScreenHeader
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.screen.chat.profile.ChatProfileState
import co.electriccoin.zcash.ui.screen.chat.profile.ChatProfileTab
import co.electriccoin.zcash.ui.screen.chat.profile.ChatProfileWalletSubTab

@Composable
internal fun ChatProfileView(state: ChatProfileState, modifier: Modifier = Modifier) {
    val c = ZappTheme.colors

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(c.bg)
                .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        ZappScreenHeader(title = state.title.getValue())

        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (state.activeTab) {
                ChatProfileTab.MESSAGING_ID -> MessagingIdTabContent(state = state)
                ChatProfileTab.WALLET_ADDRESS -> WalletAddressTabContent(state = state)
            }

            KeyExportRows(
                onSeedPhraseClick = state.onSeedPhraseClick,
                onP2pKeyClick = state.onP2pKeyClick,
                showP2pKey = state.activeTab == ChatProfileTab.WALLET_ADDRESS,
            )
        }

        if (state.activeTab == ChatProfileTab.WALLET_ADDRESS && state.shieldedAddress != null) {
            ProfileSegmentedRow(
                items =
                    listOf(
                        SegmentItem(
                            label = stringResource(R.string.chat_profile_subtab_shielded),
                            icon = Icons.Default.Security,
                            isSelected = state.walletSubTab == ChatProfileWalletSubTab.SHIELDED,
                        ),
                        SegmentItem(
                            label = stringResource(R.string.chat_profile_subtab_transparent),
                            icon = Icons.Default.CreditCard,
                            isSelected = state.walletSubTab == ChatProfileWalletSubTab.TRANSPARENT,
                        ),
                    ),
                onSelect = { idx ->
                    state.onWalletSubTabSelected(
                        if (idx == 0) ChatProfileWalletSubTab.SHIELDED else ChatProfileWalletSubTab.TRANSPARENT,
                    )
                },
            )
            Spacer(Modifier.height(4.dp))
        }

        ProfileSegmentedRow(
            items =
                listOf(
                    SegmentItem(
                        label = stringResource(R.string.chat_profile_tab_messaging_id),
                        icon = null,
                        isSelected = state.activeTab == ChatProfileTab.MESSAGING_ID,
                    ),
                    SegmentItem(
                        label = stringResource(R.string.chat_profile_tab_wallet_address),
                        icon = null,
                        isSelected = state.activeTab == ChatProfileTab.WALLET_ADDRESS,
                    ),
                ),
            onSelect = { idx ->
                state.onMainTabSelected(
                    if (idx == 0) ChatProfileTab.MESSAGING_ID else ChatProfileTab.WALLET_ADDRESS,
                )
            },
        )

        Spacer(Modifier.height(12.dp))

        BottomDock(onBack = state.onBack, onDelete = state.onDeleteClick)
    }

    state.editNameDialog?.let { EditDisplayNameDialog(state = it) }
    state.deleteDialog?.let { DeleteIdentityDialog(state = it) }
    state.pinVerify?.let { PinVerifyOverlay(state = it) }
    state.seedPhraseDialog?.let { SeedPhraseDialog(state = it) }
    state.p2pKeyDialog?.let { P2pWalletKeyDialog(state = it) }
}
