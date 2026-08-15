// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.p2pkey

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.compose.SecureScreen
import co.electriccoin.zcash.ui.common.compose.shouldSecureScreen
import co.electriccoin.zcash.ui.design.component.ZashiScreenModalBottomSheet
import co.electriccoin.zcash.ui.design.component.zapp.ZappBorderedCard
import co.electriccoin.zcash.ui.design.component.zapp.ZappBottomActionBar
import co.electriccoin.zcash.ui.design.component.zapp.ZappButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappCopyIconButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappGroupHeader
import co.electriccoin.zcash.ui.design.component.zapp.ZappScreenHeader
import co.electriccoin.zcash.ui.design.component.zapp.ZappValueCard
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.screen.chat.view.PinVerifyOverlay

@Composable
internal fun ChatP2pKeyView(
    state: ChatP2pKeyState,
    modifier: Modifier = Modifier,
) {
    val c = ZappTheme.colors
    var showInfo by rememberSaveable { mutableStateOf(false) }

    if (state.ownerKey != null && shouldSecureScreen) {
        SecureScreen()
    }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(c.bg)
                .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout)),
    ) {
        ScreenHeader(onInfoClick = { showInfo = true })

        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
        ) {
            state.smartAccountAddress?.let { address ->
                ZappGroupHeader(text = stringResource(R.string.chat_p2p_key_smart_account_label))
                ZappValueCard(
                    value = address,
                    caption = stringResource(R.string.chat_p2p_key_smart_account_caption),
                    trailing = { CopyButton(value = address, state = state) },
                )
            }

            ZappGroupHeader(text = stringResource(R.string.chat_p2p_key_owner_label))
            when (val ownerKey = state.ownerKey) {
                null -> LockedOwnerKeyCard(onRevealClick = state.onRevealClick)
                else -> RevealedOwnerKey(ownerKey = ownerKey, state = state)
            }

            Spacer(Modifier.height(16.dp))
        }

        ZappBottomActionBar(onBack = state.onBack)
    }

    state.pinVerify?.let { PinVerifyOverlay(state = it) }

    if (showInfo) {
        P2pKeyInfoSheet(onDismiss = { showInfo = false })
    }
}

@Composable
private fun LockedOwnerKeyCard(onRevealClick: () -> Unit) {
    val c = ZappTheme.colors
    ZappBorderedCard(
        modifier = Modifier.padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BasicText(
            text = stringResource(R.string.chat_p2p_key_owner_locked_body),
            style = ZappTheme.typography.body.copy(color = c.textMuted),
        )
        ZappButton(
            text = stringResource(R.string.chat_p2p_key_reveal),
            onClick = onRevealClick,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun RevealedOwnerKey(
    ownerKey: ChatP2pOwnerKey,
    state: ChatP2pKeyState,
) {
    ZappValueCard(
        value = ownerKey.address,
        label = stringResource(R.string.chat_p2p_key_owner_address_label),
        caption = stringResource(R.string.chat_p2p_key_owner_address_caption),
        trailing = { CopyButton(value = ownerKey.address, state = state) },
    )
    Spacer(Modifier.height(12.dp))
    ZappValueCard(
        value = ownerKey.privateKeyHex,
        label = stringResource(R.string.chat_p2p_key_private_key_label),
        caption = stringResource(R.string.chat_p2p_key_private_key_caption),
        trailing = { CopyButton(value = ownerKey.privateKeyHex, state = state) },
    )
}

@Composable
private fun CopyButton(
    value: String,
    state: ChatP2pKeyState,
) {
    ZappCopyIconButton(
        isCopied = state.copiedValue == value,
        contentDescription = stringResource(R.string.chat_p2p_key_copy_content_description),
        onClick = { state.onCopyClick(value) },
    )
}

@Composable
private fun ScreenHeader(onInfoClick: () -> Unit) {
    val c = ZappTheme.colors
    ZappScreenHeader(
        title = stringResource(R.string.chat_p2p_key_title),
        right = {
            IconButton(onClick = onInfoClick, modifier = Modifier.size(32.dp)) {
                Icon(
                    painter = painterResource(co.electriccoin.zcash.ui.design.R.drawable.ic_info),
                    contentDescription = stringResource(R.string.chat_p2p_key_info_content_description),
                    tint = c.text,
                    modifier = Modifier.size(20.dp),
                )
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun P2pKeyInfoSheet(onDismiss: () -> Unit) {
    val c = ZappTheme.colors
    ZashiScreenModalBottomSheet(onDismissRequest = onDismiss) { contentPadding ->
        Column(
            modifier =
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(start = 24.dp, end = 24.dp, bottom = contentPadding.calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BasicText(
                text = stringResource(R.string.chat_p2p_key_info_title),
                style = ZappTheme.typography.sectionTitle.copy(color = c.text),
            )
            listOf(
                R.string.chat_p2p_key_info_derivation,
                R.string.chat_p2p_key_info_smart_account,
                R.string.chat_p2p_key_info_safety,
            ).forEach { paragraph ->
                BasicText(
                    text = stringResource(paragraph),
                    style = ZappTheme.typography.body.copy(color = c.textMuted),
                )
            }
        }
    }
}

@PreviewScreens
@Composable
private fun ChatP2pKeyPreview() =
    ZcashTheme {
        ChatP2pKeyView(
            state =
                ChatP2pKeyState(
                    smartAccountAddress = "0x1234567890abcdef1234567890abcdef12345678",
                    ownerKey = null,
                    copiedValue = null,
                    onCopyClick = {},
                    onRevealClick = {},
                    onBack = {},
                    pinVerify = null,
                ),
        )
    }
