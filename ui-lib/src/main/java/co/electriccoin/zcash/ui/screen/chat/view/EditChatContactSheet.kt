// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.view

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.usecase.CopyToClipboardUseCase
import co.electriccoin.zcash.ui.design.component.ZashiModalBottomSheet
import co.electriccoin.zcash.ui.design.component.zapp.ZAPP_INPUT_FIELD_HEIGHT
import co.electriccoin.zcash.ui.design.component.zapp.ZappInputField
import co.electriccoin.zcash.ui.design.component.zapp.ellipsizeAddress
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.screen.chat.contacts.EditChatContactState
import org.koin.compose.koinInject

/**
 * "Edit chat contact" bottom sheet. All form state owned by `EditChatContactVM`
 * via the parent `ChatContactsVM`; this composable is purely declarative.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditChatContactSheet(state: EditChatContactState) {
    val c = ZappTheme.colors
    val keyboard = LocalSoftwareKeyboardController.current
    val copyToClipboard = koinInject<CopyToClipboardUseCase>()
    val shortKey = remember(state.publicKey) { state.publicKey.ellipsizeAddress() }
    val scanWalletAddressLabel = stringResource(R.string.chat_contact_scan_wallet_address_content_description)
    val saveChangesLabel = stringResource(R.string.chat_contact_edit_save_changes_content_description)
    val blockUserLabel = stringResource(R.string.chat_contact_edit_block_content_description)
    val unblockUserLabel = stringResource(R.string.chat_contact_edit_unblock_content_description)
    val deleteContactLabel = stringResource(R.string.chat_contact_edit_delete_contact_content_description)

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
                text = stringResource(R.string.chat_contact_edit_title),
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
                trailingIcon = {
                    CopyFieldAction(
                        contentDescription = stringResource(R.string.chat_contact_copy_name_content_description),
                        enabled = state.name.text.isNotBlank(),
                        onClick = { copyToClipboard(state.name.text) },
                    )
                },
            )

            Spacer(Modifier.height(12.dp))

            // Messaging key — read-only display
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(ZAPP_INPUT_FIELD_HEIGHT)
                        .background(c.surfaceInput, RectangleShape)
                        .border(BorderStroke(1.dp, c.border), RectangleShape)
                        .padding(start = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Key,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = c.textSubtle,
                )
                Spacer(Modifier.width(10.dp))
                BasicText(
                    text = shortKey,
                    style = ZappTheme.typography.mono.copy(color = c.textMuted),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                CopyFieldAction(
                    contentDescription = stringResource(R.string.chat_contact_copy_messaging_key_content_description),
                    onClick = { copyToClipboard(state.publicKey) },
                )
            }

            Spacer(Modifier.height(12.dp))

            // Wallet Address field (editable, Unified)
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
                    CopyFieldAction(
                        contentDescription =
                            stringResource(R.string.chat_contact_copy_wallet_address_content_description),
                        enabled = state.walletAddress.text.isNotBlank(),
                        onClick = { copyToClipboard(state.walletAddress.text) },
                    )
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

            // Delete confirmation inline
            if (state.showDeleteConfirm) {
                DeleteConfirmation(
                    onCancel = state.onCancelDelete,
                    onConfirm = state.onConfirmDelete,
                )
            }

            // Save + Delete CTAs
            if (!state.showDeleteConfirm) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .background(
                                if (state.isSaveEnabled) c.accent else c.surfaceAlt,
                                RectangleShape,
                            ).then(
                                if (state.isSaveEnabled) {
                                    Modifier.clickable(onClick = {
                                        keyboard?.hide()
                                        state.onSave()
                                    })
                                } else {
                                    Modifier
                                }
                            ).semantics {
                                contentDescription = saveChangesLabel
                                role = Role.Button
                                if (!state.isSaveEnabled) disabled()
                            },
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(
                        text = stringResource(R.string.chat_contact_edit_save_changes_button),
                        style =
                            ZappTheme.typography.button.copy(
                                color = if (state.isSaveEnabled) c.onAccent else c.textSubtle,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 0.6.sp,
                            ),
                    )
                }

                state.onBlock?.let { onBlock ->
                    Spacer(Modifier.height(10.dp))
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .border(BorderStroke(1.dp, c.border), RectangleShape)
                                .clickable(onClick = onBlock)
                                .semantics {
                                    contentDescription = if (state.isBlocked) unblockUserLabel else blockUserLabel
                                    role = Role.Button
                                },
                        contentAlignment = Alignment.Center,
                    ) {
                        BasicText(
                            text =
                                stringResource(
                                    if (state.isBlocked) {
                                        R.string.chat_contact_edit_unblock_button
                                    } else {
                                        R.string.chat_contact_edit_block_button
                                    }
                                ),
                            style =
                                ZappTheme.typography.button.copy(
                                    color = c.text,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 0.6.sp,
                                ),
                        )
                    }
                }

                if (state.canDelete) {
                    Spacer(Modifier.height(10.dp))

                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .background(c.dangerSoft, RectangleShape)
                                .clickable(onClick = state.onRequestDelete)
                                .semantics {
                                    contentDescription = deleteContactLabel
                                    role = Role.Button
                                },
                        contentAlignment = Alignment.Center,
                    ) {
                        BasicText(
                            text = stringResource(R.string.chat_contact_edit_delete_contact_button),
                            style =
                                ZappTheme.typography.button.copy(
                                    color = c.danger,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 0.6.sp,
                                ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CopyFieldAction(
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val c = ZappTheme.colors
    Box(
        modifier =
            Modifier
                .size(48.dp)
                .clickable(enabled = enabled, onClick = onClick)
                .semantics {
                    this.contentDescription = contentDescription
                    role = Role.Button
                    if (!enabled) disabled()
                },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.ContentCopy,
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            tint = if (enabled) c.textSubtle else c.border,
        )
    }
}

@Composable
private fun DeleteConfirmation(
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    val c = ZappTheme.colors
    val cancelDeleteLabel = stringResource(R.string.chat_contact_edit_delete_cancel_content_description)
    val confirmDeleteLabel = stringResource(R.string.chat_contact_edit_delete_confirm_content_description)
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(c.dangerSoft, RectangleShape)
                .border(BorderStroke(1.dp, c.danger), RectangleShape)
                .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Column {
            BasicText(
                text = stringResource(R.string.chat_contact_edit_delete_confirm_title),
                style =
                    ZappTheme.typography.rowTitle.copy(
                        color = c.danger,
                        fontWeight = FontWeight.Black,
                    ),
            )
            Spacer(Modifier.height(4.dp))
            BasicText(
                text = stringResource(R.string.chat_contact_edit_delete_confirm_subtitle),
                style = ZappTheme.typography.rowSubtitle.copy(color = c.textMuted),
            )
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .height(48.dp)
                            .border(BorderStroke(1.dp, c.border), RectangleShape)
                            .clickable(onClick = onCancel)
                            .semantics {
                                contentDescription = cancelDeleteLabel
                                role = Role.Button
                            },
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(
                        text = stringResource(R.string.chat_contact_edit_delete_cancel_button),
                        style =
                            ZappTheme.typography.button.copy(
                                color = c.text,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 0.6.sp,
                            ),
                    )
                }
                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .height(48.dp)
                            .background(c.danger, RectangleShape)
                            .clickable(onClick = onConfirm)
                            .semantics {
                                contentDescription = confirmDeleteLabel
                                role = Role.Button
                            },
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(
                        text = stringResource(R.string.chat_contact_edit_delete_confirm_button),
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
}
