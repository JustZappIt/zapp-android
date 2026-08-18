// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.view

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.ZashiModalBottomSheet
import co.electriccoin.zcash.ui.design.component.zapp.ZappInputField
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.screen.chat.contacts.AddChatContactState

/**
 * "Add new chat contact" bottom sheet. State is owned by `AddChatContactVM`
 * via the parent `ChatContactsVM`; this composable is purely declarative.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddChatContactSheet(state: AddChatContactState) {
    val c = ZappTheme.colors
    val keyboard = LocalSoftwareKeyboardController.current
    val scanMessagingKeyLabel = stringResource(R.string.chat_contact_scan_messaging_key_content_description)
    val scanWalletAddressLabel = stringResource(R.string.chat_contact_scan_wallet_address_content_description)
    val saveLabel = stringResource(R.string.chat_contact_add_save_content_description)

    ZashiModalBottomSheet(
        onDismissRequest = state.onDismiss,
        containerColor = c.surface,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(bottom = 28.dp),
        ) {
            BasicText(
                text = stringResource(R.string.chat_contact_add_title),
                style =
                    ZappTheme.typography.sectionTitle.copy(
                        color = c.text,
                        fontWeight = FontWeight.Black,
                    ),
            )

            Spacer(Modifier.height(20.dp))

            // Name field
            ZappInputField(
                value = state.name,
                onValueChange = state.onNameChange,
                placeholder = stringResource(R.string.contact_name_hint),
                leadingIcon = {
                    Icon(
                        Icons.Default.Person,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = c.textSubtle,
                    )
                },
            )

            Spacer(Modifier.height(12.dp))

            // Messaging Key field
            ZappInputField(
                value = state.publicKey,
                onValueChange = state.onPublicKeyChange,
                placeholder = stringResource(R.string.chat_contact_add_messaging_key_placeholder),
                leadingIcon = {
                    Icon(
                        Icons.Default.Key,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = c.textSubtle,
                    )
                },
                trailingIcon = {
                    Box(
                        modifier =
                            Modifier
                                .size(48.dp)
                                .clickable(onClick = state.onScanPublicKey)
                                .semantics {
                                    contentDescription = scanMessagingKeyLabel
                                    role = Role.Button
                                },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.QrCodeScanner,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = c.textSubtle,
                        )
                    }
                },
            )

            // Valid key confirmation row
            if (state.isValidKey) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(c.successSoft, RectangleShape)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = c.success,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    BasicText(
                        text = "${state.cleanedKey.take(10)}…${state.cleanedKey.takeLast(6)}",
                        style = ZappTheme.typography.chip.copy(color = c.success),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // Wallet Address field (Unified)
            ZappInputField(
                value = state.walletAddress,
                onValueChange = state.onWalletAddressChange,
                placeholder = stringResource(R.string.contact_address_hint),
                leadingIcon = {
                    Icon(
                        Icons.Default.AccountBalanceWallet,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = c.textSubtle,
                    )
                },
                trailingIcon = {
                    Box(
                        modifier =
                            Modifier
                                .size(48.dp)
                                .clickable(onClick = state.onScanWalletAddress)
                                .semantics {
                                    contentDescription = scanWalletAddressLabel
                                    role = Role.Button
                                },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.QrCodeScanner,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = c.textSubtle,
                        )
                    }
                },
            )

            state.error?.let {
                Spacer(Modifier.height(8.dp))
                BasicText(
                    text = it.getValue(),
                    style = ZappTheme.typography.caption.copy(color = c.danger),
                )
            }

            Spacer(Modifier.height(16.dp))

            // DEAD CODE [hidden]: Additional Addresses section — uncomment to restore, plus the
            // co.electriccoin.zcash.ui.screen.addressbook.WalletAddressesSection import
            // WalletAddressesSection(
            //     expanded = state.showAdditionalAddresses,
            //     onToggle = state.onToggleAdditionalAddresses,
            //     transparentAddr = state.transparentAddr,
            //     onTransparentChange = state.onTransparentAddrChange,
            //     evmAddr = state.evmAddr,
            //     onEvmChange = state.onEvmAddrChange,
            //     solanaAddr = state.solanaAddr,
            //     onSolanaChange = state.onSolanaAddrChange,
            //     onScanAddress = state.onScanAddressField,
            // )

            Spacer(Modifier.height(20.dp))

            // Add Contact primary CTA
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .background(c.accent, RectangleShape)
                        .clickable(onClick = {
                            keyboard?.hide()
                            state.onSave()
                        })
                        .semantics {
                            contentDescription = saveLabel
                            role = Role.Button
                        },
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text = stringResource(R.string.chat_contact_add_save_button),
                    style =
                        ZappTheme.typography.button.copy(
                            color = c.onAccent,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 0.6.sp,
                        ),
                )
            }
        }
    }
}
