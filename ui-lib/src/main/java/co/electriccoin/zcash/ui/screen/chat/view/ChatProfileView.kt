// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.view

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material3.Icon
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.zapp.ZappBottomActionBar
import co.electriccoin.zcash.ui.design.component.zapp.ZappButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappGroupHeader
import co.electriccoin.zcash.ui.design.component.zapp.ZappRow
import co.electriccoin.zcash.ui.design.component.zapp.ZappRowDivider
import co.electriccoin.zcash.ui.design.component.zapp.ZappScreenHeader
import co.electriccoin.zcash.ui.design.component.zapp.ZappSettingsGroup
import co.electriccoin.zcash.ui.design.component.zapp.ZappValueCard
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.screen.chat.profile.ChatProfileState

@Composable
internal fun ChatProfileView(
    state: ChatProfileState,
    modifier: Modifier = Modifier,
) {
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
                    .verticalScroll(rememberScrollState()),
        ) {
            DisplayNameRow(
                displayName = state.displayName.orEmpty(),
                onEditClick = state.onEditDisplayNameClick,
            )

            state.publicKey?.let { publicKey ->
                ZappValueCard(
                    value = publicKey,
                    label = stringResource(R.string.chat_profile_public_key_label),
                )
            }

            ZappSettingsGroup(title = stringResource(R.string.chat_profile_group_identity)) {
                ZappRow(
                    title = stringResource(R.string.chat_wallet_address_title),
                    subtitle = stringResource(R.string.chat_wallet_address_subtitle),
                    icon = Icons.Default.QrCode,
                    iconTint = c.accentText,
                    iconBackground = c.accentSoft,
                    onClick = state.onWalletAddressClick,
                )
                ZappRowDivider(inset = true)
                ZappRow(
                    title = stringResource(R.string.chat_profile_seed_phrase_title),
                    subtitle = stringResource(R.string.chat_profile_seed_phrase_subtitle),
                    icon = Icons.Default.Key,
                    iconTint = c.accentText,
                    iconBackground = c.accentSoft,
                    onClick = state.onSeedPhraseClick,
                )
                ZappRowDivider(inset = true)
                ZappRow(
                    title = stringResource(R.string.chat_profile_p2p_key_title),
                    subtitle = stringResource(R.string.chat_profile_p2p_key_subtitle),
                    icon = Icons.Default.AccountBalanceWallet,
                    iconTint = c.accentText,
                    iconBackground = c.accentSoft,
                    onClick = state.onP2pKeyClick,
                )
            }

            ZappSettingsGroup(title = stringResource(R.string.chat_profile_group_danger_zone)) {
                ZappRow(
                    title = stringResource(R.string.chat_profile_delete_identity_title),
                    subtitle = stringResource(R.string.chat_profile_delete_identity_subtitle),
                    titleColor = c.danger,
                    onClick = state.onDeleteClick,
                )
            }

            Spacer(Modifier.height(16.dp))
        }

        ZappBottomActionBar(
            onBack = state.onBack,
            primaryAction = {
                ZappButton(
                    text =
                        if (state.isKeyCopied) {
                            stringResource(R.string.chat_profile_copied_content_description)
                        } else {
                            stringResource(R.string.chat_profile_copy_public_key_content_description)
                        },
                    leadingIcon = Icons.Default.ContentCopy,
                    onClick = state.onCopyPublicKeyClick,
                    enabled = state.publicKey != null,
                    modifier =
                        Modifier
                            .weight(1f)
                            .padding(start = 12.dp),
                )
            },
        )
    }

    state.editNameDialog?.let { EditDisplayNameDialog(state = it) }
    state.deleteDialog?.let { DeleteIdentityDialog(state = it) }
    state.pinVerify?.let { PinVerifyOverlay(state = it) }
    state.seedPhraseDialog?.let { SeedPhraseDialog(state = it) }
}

@Composable
private fun DisplayNameRow(
    displayName: String,
    onEditClick: () -> Unit,
) {
    val c = ZappTheme.colors
    val editLabel = stringResource(R.string.chat_profile_edit_display_name_content_description)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
    ) {
        BasicText(
            text = "@$displayName",
            style = ZappTheme.typography.sectionTitle.copy(color = c.text),
        )
        Box(
            modifier =
                Modifier
                    .size(48.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(bounded = false),
                        onClick = onEditClick,
                    ).semantics {
                        contentDescription = editLabel
                        role = Role.Button
                    },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Edit,
                contentDescription = null,
                tint = c.textMuted,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}
