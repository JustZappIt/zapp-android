// SPDX-License-Identifier: MIT OR Apache-2.0
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
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
import co.electriccoin.zcash.ui.design.component.QrState
import co.electriccoin.zcash.ui.design.component.ZashiQr
import co.electriccoin.zcash.ui.design.component.zapp.initialsOf
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.screen.chat.profile.ChatProfileState
import co.electriccoin.zcash.ui.screen.chat.profile.ChatProfileWalletSubTab

@Composable
internal fun MessagingIdTabContent(state: ChatProfileState) {
    val c = ZappTheme.colors
    val initials = remember(state.displayName) { state.displayName?.let { initialsOf(it) } ?: "?" }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(72.dp)
                    .background(c.accent, RectangleShape),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text = initials,
                style = ZappTheme.typography.sectionTitle.copy(color = c.onAccent),
            )
        }
        val editLabel = stringResource(R.string.chat_profile_edit_display_name_content_description)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            BasicText(
                text = state.displayName.orEmpty(),
                style = ZappTheme.typography.sectionTitle.copy(color = c.text),
            )
            Box(
                modifier =
                    Modifier
                        .size(32.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(bounded = false),
                            onClick = state.onEditDisplayNameClick,
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

    state.publicKey?.let { pk ->
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(c.surface, RectangleShape)
                    .border(BorderStroke(1.dp, c.border), RectangleShape)
                    .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ZashiQr(state = QrState(qrData = pk), qrSize = 160.dp)
                BasicText(
                    text = stringResource(R.string.chat_profile_qr_caption),
                    style = ZappTheme.typography.caption.copy(color = c.textSubtle),
                )
            }
        }

        PublicKeyCard(
            publicKey = pk,
            showCopiedFeedback = state.isKeyCopied,
            onCopy = state.onCopyPublicKeyClick,
        )
    }
}

@Composable
internal fun WalletAddressTabContent(state: ChatProfileState) {
    val c = ZappTheme.colors
    val address =
        when (state.walletSubTab) {
            ChatProfileWalletSubTab.SHIELDED -> state.shieldedAddress.orEmpty()
            ChatProfileWalletSubTab.TRANSPARENT -> state.transparentAddress.orEmpty()
        }
    val addressLabel =
        when (state.walletSubTab) {
            ChatProfileWalletSubTab.SHIELDED -> {
                stringResource(R.string.chat_profile_address_shielded_label)
            }

            ChatProfileWalletSubTab.TRANSPARENT -> {
                stringResource(R.string.chat_profile_address_transparent_label)
            }
        }
    val caption =
        when (state.walletSubTab) {
            ChatProfileWalletSubTab.SHIELDED -> {
                stringResource(R.string.chat_profile_address_shielded_caption)
            }

            ChatProfileWalletSubTab.TRANSPARENT -> {
                stringResource(R.string.chat_profile_address_transparent_caption)
            }
        }

    if (address.isNotEmpty()) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(c.surface, RectangleShape)
                    .border(BorderStroke(1.dp, c.border), RectangleShape)
                    .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ZashiQr(state = QrState(qrData = address), qrSize = 200.dp)
                BasicText(
                    text = caption,
                    style = ZappTheme.typography.caption.copy(color = c.textSubtle),
                )
            }
        }

        AddressCard(
            label = addressLabel,
            address = address,
            showCopiedFeedback = state.isAddressCopied,
            onCopy = state.onCopyAddressClick,
        )
    }

    state.baseAddress?.let { base ->
        AddressCard(
            label = stringResource(R.string.chat_profile_address_base_label),
            address = base,
            showCopiedFeedback = state.isBaseAddressCopied,
            onCopy = state.onCopyBaseAddressClick,
        )
    }
}

@Composable
private fun PublicKeyCard(publicKey: String, showCopiedFeedback: Boolean, onCopy: () -> Unit) {
    val c = ZappTheme.colors
    val copyLabel =
        if (showCopiedFeedback) {
            stringResource(R.string.chat_profile_copied_content_description)
        } else {
            stringResource(R.string.chat_profile_copy_public_key_content_description)
        }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(c.surfaceAlt, RectangleShape)
                .border(BorderStroke(1.dp, c.border), RectangleShape)
                .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = stringResource(R.string.chat_profile_public_key_label),
                style = ZappTheme.typography.caption.copy(color = c.textMuted),
            )
            Spacer(Modifier.height(4.dp))
            BasicText(
                text = publicKey,
                style = ZappTheme.typography.mono.copy(color = c.text),
                maxLines = 3,
            )
        }
        Spacer(Modifier.width(8.dp))
        CopyIconButton(showCopiedFeedback = showCopiedFeedback, contentLabel = copyLabel, onClick = onCopy)
    }
}

@Composable
private fun AddressCard(
    label: String,
    address: String,
    showCopiedFeedback: Boolean,
    onCopy: () -> Unit,
) {
    val c = ZappTheme.colors
    val copyLabel =
        if (showCopiedFeedback) {
            stringResource(R.string.chat_profile_copied_content_description)
        } else {
            stringResource(R.string.chat_profile_copy_address_content_description)
        }
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(c.surfaceAlt, RectangleShape)
                .border(BorderStroke(1.dp, c.border), RectangleShape)
                .padding(16.dp),
    ) {
        BasicText(
            text = label,
            style = ZappTheme.typography.caption.copy(color = c.textMuted),
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                text = address,
                style = ZappTheme.typography.mono.copy(color = c.text),
                modifier = Modifier.weight(1f),
                maxLines = 3,
            )
            Spacer(Modifier.width(8.dp))
            CopyIconButton(showCopiedFeedback = showCopiedFeedback, contentLabel = copyLabel, onClick = onCopy)
        }
    }
}

@Composable
private fun CopyIconButton(
    showCopiedFeedback: Boolean,
    contentLabel: String,
    onClick: () -> Unit,
) {
    val c = ZappTheme.colors
    Box(
        modifier =
            Modifier
                .size(48.dp)
                .clickable(onClick = onClick)
                .semantics {
                    contentDescription = contentLabel
                    role = Role.Button
                },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (showCopiedFeedback) Icons.Default.Check else Icons.Default.ContentCopy,
            contentDescription = null,
            tint = if (showCopiedFeedback) c.success else c.textMuted,
            modifier = Modifier.size(20.dp),
        )
    }
}
