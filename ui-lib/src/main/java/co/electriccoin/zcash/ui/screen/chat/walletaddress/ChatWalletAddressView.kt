// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.walletaddress

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.QrState
import co.electriccoin.zcash.ui.design.component.ZashiQr
import co.electriccoin.zcash.ui.design.component.zapp.ZappBottomActionBar
import co.electriccoin.zcash.ui.design.component.zapp.ZappButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappCopyIconButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappGroupHeader
import co.electriccoin.zcash.ui.design.component.zapp.ZappScreenHeader
import co.electriccoin.zcash.ui.design.component.zapp.ZappValueCard
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.stringRes

@Composable
internal fun ChatWalletAddressView(
    state: ChatWalletAddressState,
    modifier: Modifier = Modifier,
) {
    val c = ZappTheme.colors

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(c.bg)
                .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout)),
    ) {
        ZappScreenHeader(title = stringResource(R.string.chat_wallet_address_title))

        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
        ) {
            state.addresses.forEach { item ->
                AddressCard(item = item)
            }
            Spacer(Modifier.height(16.dp))
        }

        ZappBottomActionBar(onBack = state.onBack)
    }
}

@Composable
private fun AddressCard(item: ChatWalletAddressItem) {
    val copyLabel = stringResource(R.string.chat_wallet_address_copy)
    ZappGroupHeader(text = item.label.getValue())
    ZappValueCard(
        value = item.address,
        caption = item.caption.getValue(),
        leading =
            if (item.hasQrCode) {
                { AddressQrCode(address = item.address, onCopyClick = item.onCopyClick) }
            } else {
                null
            },
        trailing = {
            ZappCopyIconButton(
                isCopied = item.isCopied,
                contentDescription = copyLabel,
                onClick = item.onCopyClick,
            )
        },
    )
}

@Composable
private fun AddressQrCode(
    address: String,
    onCopyClick: () -> Unit,
) {
    ZashiQr(
        state =
            QrState(
                qrData = address,
                contentDescription = stringRes(R.string.chat_wallet_address_qr_content_description),
            ),
        modifier = Modifier.semantics { role = Role.Button },
        qrSize = 72.dp,
        contentPadding = PaddingValues(0.dp),
        fullscreenAction = {
            ZappButton(
                text = stringResource(R.string.chat_wallet_address_copy),
                leadingIcon = Icons.Default.ContentCopy,
                onClick = onCopyClick,
            )
        },
    )
}

@PreviewScreens
@Composable
private fun ChatWalletAddressPreview() =
    ZcashTheme {
        ChatWalletAddressView(
            state =
                ChatWalletAddressState(
                    addresses =
                        listOf(
                            ChatWalletAddressItem(
                                label = stringRes(R.string.chat_profile_address_shielded_label),
                                caption = stringRes(R.string.chat_profile_address_shielded_caption),
                                address = "u1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq",
                                hasQrCode = true,
                                isCopied = false,
                                onCopyClick = {},
                            ),
                        ),
                    onBack = {},
                ),
        )
    }
